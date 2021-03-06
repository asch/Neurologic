package discoverer;

import discoverer.grounding.evaluation.struct.ParentCounter;
import discoverer.construction.example.Example;
import discoverer.construction.network.KL;
import discoverer.construction.network.Kappa;
import discoverer.construction.network.Network;
import discoverer.construction.network.rules.KappaRule;
import discoverer.drawing.GroundDotter;
import discoverer.global.Global;
import discoverer.global.Glogger;
import discoverer.global.Settings;
import discoverer.grounding.ForwardChecker;
import discoverer.grounding.evaluation.Ball;
import discoverer.grounding.Grounder;
import extras.BackpropGroundKappa;
import discoverer.learning.backprop.BackpropDownAvg;
import discoverer.grounding.evaluation.Evaluator;
import discoverer.grounding.evaluation.struct.Dropout;
import discoverer.learning.Invalidator;
import discoverer.learning.Result;
import discoverer.learning.Results;
import discoverer.learning.Sample;
import discoverer.learning.Saver;
import discoverer.learning.Weights;
import discoverer.learning.backprop.BackpropDown;
import java.util.*;

/**
 * Perform learning algorithm
 */
public class Learner {

    Results results = new Results();
    int progress = 0;

    /**
     * AVG variant with no grounding-epochae implements strategy for number of
     * learning steps based on a restart number
     *
     * @param restart
     * @return
     */
    public boolean continueRestart(int step, int restart) {

        if (Global.isCumulativeDiffRestarts()) {
            return !results.convergence();
        } else {
            int steps = 500 + restart * restart;
            Glogger.process("steps for restart " + restart + " = " + steps);
            if (step < steps) {
                return true;
            }
        }
        return false;
    }

    /**
     * number of grounding-epochae to perform for a given restart number
     * similarly to the strategy of learning steps for a restart
     *
     * @param restart
     * @return
     */
    private boolean continueRestartEpochae(int epocha, int restart) {
        if (Global.isCumulativeDiffRestarts()) {
            return !results.convergence();
        } else {
            int epochae = 1 + restart * restart;
            Glogger.process("epochae for restart " + restart + " = " + epochae);
            if (epocha < epochae) {
                return true;
            }
        }
        return false;
    }

    /**
     * number of learning steps for a specific grounding-epocha running with a
     * specific restart can be a static number (10,100) or a strategy
     *
     * @param epocha
     * @param restart
     * @return
     */
    private boolean continueEpocha(int step, int epocha, int restart) {

        //int steps = 10 + 10 * epocha * restart;
        int steps = 500;

        Glogger.process("epochae for restart " + restart + " = " + steps);
        if (step < steps) {
            return true;
        }

        return false;
    }

    public double learnDecay(int step, double learnRate) {
        return Global.getLearnDecayA() / (Global.getLearnDecayB() + step);
    }

    /**
     * Method for handling the first learning run<p>
     * for each example finds it's maximal and average substitution's Ball
     * result returned as couples in RoundElement
     *
     * @param examples examples
     * @param last output node
     * @return list with balls from first run
     */
    public List<Sample> prepareGroundings(List<Example> examples, Network net) {
        //find max. and average substitution for all examples
        ForwardChecker.exnum = 0;
        Glogger.process("searching for initial substition trees for each example...");
        List<Sample> roundStore = new ArrayList<Sample>();
        ForwardChecker.exnum = 0;
        int i = 0;
        for (Example e : examples) {
            Ball b = Grounder.solve(net.last, e);
            //GroundDotter.draw(b, "sigSig" + i++);
            Glogger.info("example: " + e + " , maxVal: " + b.valMax + ", avgVal: " + b.valAvg);
            roundStore.add(new Sample(e, b));
        }

        Glogger.process("...done with intial grounding");

        //here calculate for each proof-tree(=Ball b) numbers of parents for each GroundKappa/Lambda
        for (Sample result : roundStore) {
            Ball b = result.getBall();
            if (Global.getGrounding() == Global.groundingSet.avg) {
                ParentCounter.countParentsAVG(b);
            } else if (Global.getGrounding() == Global.groundingSet.max) {
                ParentCounter.countParents(b);
            }
        }

        return roundStore;
    }

    private Results endTraining(List<Sample> roundStore, Network net) {
        //learning finished!
        Glogger.process("backpropagation on fold finished");
        //LOADING the final best model from training
        Saver.load();   //loading the weights for Kappa-Lambda program
        //-------------
        Glogger.process("Loading...Saver loaded the best network template");
        Glogger.process("Grounding the best template...");
        ForwardChecker.exnum = 0;
        results.clear();
        for (Sample roundElement : roundStore) {  //so we need to calculate the proof-tree output again
            Example e = roundElement.getExample();
            Ball b = Grounder.solve(net.last, e);   //so again create the proof-tree
            roundElement.setBall(b);
            if (Global.getGrounding() == Global.groundingSet.avg) {
                results.add(new Result(b.valAvg, e.getExpectedValue()));
                Glogger.info("example: " + e + " -> avgVal: " + b.valAvg);
            } else if (Global.getGrounding() == Global.groundingSet.max) {
                results.add(new Result(b.valMax, e.getExpectedValue()));
                Glogger.info("example: " + e + " -> maxVal: " + b.valMax);
            } else {
                throw new AssertionError();
            }
        }
        Glogger.LogTrain("final_train", new Double[]{results.getLearningError(), results.getDispersion(), results.getMajorityClass(), results.getThreshold()});
        Glogger.process("Saved training error as best of all restarts =\t" + results.getLearningError() + " (maj: " + results.getMajorityClass() + ")" + " (th: " + results.getThreshold() + ")" + " (disp: " + results.getDispersion() + ")");
        return results;
    }

    /**
     * Summing up actual weights with the new calculated gradient for general
     * Kappa nodes
     *
     * @param w
     */
    public void refreshWeights(Weights w) {
        for (Map.Entry<Object, Double> entryWeights : w.getWeights().entrySet()) {
            Object o = entryWeights.getKey();
            Double newWeight = entryWeights.getValue();
            if (o instanceof Kappa) {
                Kappa k = (Kappa) o;
                k.setOffset(k.getOffset() + newWeight);
            } else {
                KappaRule kr = (KappaRule) o;
                kr.setWeight(kr.getWeight() + newWeight);
            }
        }
    }

    /**
     * main iterative learning procedure:
     * <p>
     * <p>
     * for a number of restarts
     * <p>
     * for a number of learning epochs
     * <p>
     * for a number of learning steps
     * <p>
     * upgrade each example-generated network's weights
     *
     * @param last
     * @param examples
     * @param learningSteps
     * @param learningEpochs
     * @param restartCount
     * @param learnRate
     * @return
     */
    public Results solveMax(Network last, List<Example> examples) {

        List<Sample> roundStore = prepareGroundings(examples, last);

        Glogger.process("-------------solveMax----------------");
        for (int a = 0; a < Settings.restartCount; a++) {    //restarting the whole procedure
            if (Global.isInitWithAVG()) {
                Glogger.process("----initializing weights with AVG variant----");
                solveAvg(last, examples);
                results = new Results();
            }
            Glogger.process("---------------------------------------------------------------------------------------------------------------------");
            Glogger.process("------------Restart: " + a);
            for (int x = 0; x < Settings.learningEpochs; x++) {      //learningEpochs = maximal substitution cycles for all examples
                Glogger.process("-------epochae: " + x);
                results.past.clear();
                for (int i = 0; i < Settings.learningSteps; i++) {       //learningSteps = backpropagation steps
                    if (Global.isLearnDecay()) {
                        Settings.learnRate = learnDecay(i + (x * Settings.learningSteps), Settings.learnRate);
                    }
                    Glogger.process("------learning step: " + i);
                    if (Global.isSGD()) {
                        Collections.shuffle(roundStore, Global.getRg());    //stochastic gradient descend!
                    }
                    for (Sample result : roundStore) {     //for each example(result)
                        Example e = result.getExample();
                        Ball b = result.getBall();
                        double old = b.valMax;
                        if (Global.getDropout() > 0) {
                            Dropout.dropoutMax(b);
                            Evaluator.ignoreDropout = false;
                            b.valMax = Evaluator.evaluateMax(b);
                            Evaluator.ignoreDropout = true;
                        } else {
                            b.valMax = Evaluator.evaluateMax(b);  //forward propagation
                        }
                        Glogger.debug("Example: " + e + "Example's weight change from last minibatch (after 1-bp over all other examples) " + old + " -> " + b.valAvg);
                        Weights w = BackpropDown.getNewWeights(b, e);  //backpropagation
                        refreshWeights(w);  //update weights
                    }   //learning errors on this fixed ground tree (found as max subst before learning)
                    Glogger.process("preliminary train error without regrounding...:");
                    evaluate(roundStore);
                }
                reGround(roundStore, last);
            }
            saveTemplate(roundStore, last);
            Glogger.LogTrain("...restart " + a);
            Invalidator.invalidate(last);       //reset all weights before restart
        }

        endTraining(roundStore, last);

        return results;
    }

    /**
     * AVG version is principally different - new procedure
     *
     * @param last
     * @param examples
     * @param learningSteps
     * @param learningEpochs
     * @param restartCount
     * @param learnRate
     * @return
     */
    public Results solveAvg(Network last, List<Example> examples) {

        List<Sample> roundStore = prepareGroundings(examples, last);   //this stays as with no pruning both avg and max are found

        for (int a = 0; a < Settings.restartCount; a++) {    //restarting the whole procedure
            results.past.clear();
            Glogger.process("--------SolveAVG-----------------------------------------------------------------------------------------------------------------------");
            Glogger.process("------------Restart: " + a);
            for (int i = 0; i < Settings.learningSteps; i++) {       //learningSteps = backpropagation steps
                if (Global.isLearnDecay()) {
                    Settings.learnRate = learnDecay(i, Settings.learnRate);
                }
                Glogger.process("---learning step: " + i);
                if (Global.isSGD()) {
                    Collections.shuffle(roundStore, Global.getRg());    //stochastic gradient descend!
                }
                for (Sample result : roundStore) {     //for each example(result)
                    Example e = result.getExample();
                    Ball b = result.getBall();
                    double old = b.valAvg;
                    if (Global.getDropout() > 0) {
                        Dropout.dropoutAvg(b);
                        Evaluator.ignoreDropout = false;
                        b.valAvg = Evaluator.evaluateAvg(b);
                        Evaluator.ignoreDropout = true;
                    } else {
                        b.valAvg = Evaluator.evaluateAvg(b);  //forward propagation
                    }
                    Glogger.debug("Example: " + e + "Weight change from last minibatch (after 1-bp over all other examples) " + old + " -> " + b.valAvg);
                    Weights w = BackpropDownAvg.getNewWeights(b, e);  //backpropagation
                    refreshWeights(w);  //update weights
                }
                if (Global.isSave()) {
                    saveTemplate(roundStore, last);
                }
            }
            saveTemplate(roundStore, last);
            Glogger.LogTrain("...restart " + a);
            Invalidator.invalidate(last);       //reset all weights before restart
        }

        endTraining(roundStore, last);

        return results;
    }

    /**
     * iterative AVG version is principally different - new procedure
     *
     * @param last
     * @param examples
     * @param learningSteps
     * @param learningEpochs
     * @param restartCount
     * @param learnRate
     * @return
     */
    public Results solveAvgIterative(Network last, List<Example> examples) {

        List<Sample> roundStore = prepareGroundings(examples, last);   //this stays as with no pruning both avg and max are found

        int restart = 0;
        int step = 0;
        boolean learn = true;
        while (learn) {    //restarting the whole procedure
            Glogger.process("-------------------------------------------------------------------------------------------------------------------------------");
            Glogger.process("------------Restart: " + restart);
            int i = 0;
            results.past.clear();
            while (continueRestart(i++, restart)) {       //learningSteps = backpropagation steps
                if (step++ >= Global.getCumMaxSteps()) {
                    learn = false;
                    break;
                }
                if (Global.isLearnDecay()) {
                    Settings.learnRate = learnDecay(i, Settings.learnRate);
                }
                Glogger.process("---learning step: " + i);
                if (Global.isSGD()) {
                    Collections.shuffle(roundStore, Global.getRg());    //stochastic gradient descend!
                }
                for (Sample result : roundStore) {     //for each example(result)
                    Example e = result.getExample();
                    Ball b = result.getBall();
                    double old = b.valAvg;
                    if (Global.getDropout() > 0) {
                        Dropout.dropoutAvg(b);
                        Evaluator.ignoreDropout = false;
                        b.valAvg = Evaluator.evaluateAvg(b);
                        Evaluator.ignoreDropout = true;
                    } else {
                        b.valAvg = Evaluator.evaluateAvg(b);  //forward propagation
                    }
                    Glogger.debug("Example: " + e + "Weight change from last minibatch (after 1-bp over all other examples) " + old + " -> " + b.valAvg);
                    Weights w = BackpropDownAvg.getNewWeights(b, e);  //backpropagation
                    refreshWeights(w);  //update weights
                }

                if (Global.isSave()) {
                    saveTemplate(roundStore, last);
                }
            }
            saveTemplate(roundStore, last);
            Glogger.LogTrain("...end of restart " + restart);
            Invalidator.invalidate(last);       //reset all weights before restart
            restart++;
        }

        endTraining(roundStore, last);

        return results;
    }

    /**
     * iterative max version
     *
     * @param last
     * @param examples
     * @param learningSteps
     * @param learningEpochs
     * @param restartCount
     * @param learnRate
     * @return
     */
    public Results solveMaxIterative(Network last, List<Example> examples) {

        List<Sample> roundStore = prepareGroundings(examples, last);

        int restart = 0;
        int step = 0;
        boolean learn = true;
        while (learn) {    //restarting the whole procedure
            Glogger.process("-------------------------------------------------------------------------------------------------------------------------------");
            Glogger.LogTrain("----Restart: " + restart);
            int epochae = 0;

            if (Global.isInitWithAVG()) {
                Glogger.process("----initializing weights with AVG variant----");
                solveAvg(last, examples);
                results = new Results();
            }

            results.past.clear();
            while (learn & continueRestartEpochae(epochae++, restart)) {       //learningSteps = backpropagation steps

                Glogger.LogTrain("---epochae: " + epochae);
                int i = 0;
                while (continueEpocha(i, epochae, restart)) {       //learningSteps = backpropagation steps
                    Glogger.process("------learning step: " + i++);
                    if (step++ >= Global.getCumMaxSteps()) {
                        learn = false;
                        break;
                    }
                    if (Global.isLearnDecay()) {
                        Settings.learnRate = learnDecay(epochae * i, Settings.learnRate);
                    }
                    if (Global.isSGD()) {
                        Collections.shuffle(roundStore, Global.getRg());    //stochastic gradient descend!
                    }
                    for (Sample result : roundStore) {     //for each example(result)
                        Example e = result.getExample();
                        Ball b = result.getBall();
                        double old = b.valMax;
                        if (Global.getDropout() > 0) {
                            Dropout.dropoutMax(b);
                            Evaluator.ignoreDropout = false;
                            b.valMax = Evaluator.evaluateMax(b);
                            Evaluator.ignoreDropout = true;
                        } else {
                            b.valMax = Evaluator.evaluateMax(b);  //forward propagation
                        }
                        Glogger.debug("Example: " + e + "Example's weight change from last minibatch (after 1-bp over all other examples) " + old + " -> " + b.valAvg);
                        Weights w = BackpropDown.getNewWeights(b, e);  //backpropagation
                        refreshWeights(w);  //update weights
                    }   //learning errors on this fixed ground tree (found as max subst before learning)}
                }
                reGround(roundStore, last);
            }
            saveTemplate(roundStore, last);
            Glogger.LogTrain("...restart " + restart);
            Invalidator.invalidate(last);       //reset all weights before restart
            restart++;
        }

        Glogger.LogTrain(Global.getCumMaxSteps() + " cumulative learning steps depleted");

        results = endTraining(roundStore, last);

        return results;
    }

    private Results saveTemplate(List<Sample> roundStore, Network net) {
        //need to evaluate results for the whole batch separatelly (after all example evaluations)
        evaluate(roundStore);

        double le = results.getLearningError();
        double th = results.getThreshold();
        double disp = results.getDispersion();

        if (Saver.isBetterThenBest(le, th, disp)) {
            Saver.save(net, le, th, disp);     //save the best network (last = output node)
            net.exportWeightMatrix("progress" + progress++);
        }
        //Kappa llast = (Kappa) last;
        //Dotter.draw(last, new HashSet(llast.getRules()));
        return results;
    }

    private void evaluate(List<Sample> roundStore) throws AssertionError {
        results.clear();
        for (Sample result : roundStore) {
            Example e = result.getExample();
            Ball b = result.getBall();
            if (Global.getGrounding() == Global.groundingSet.avg) {
                double old = b.valAvg;
                b.valAvg = Evaluator.evaluateAvg(b);  //forward propagation
                results.add(new Result(b.valAvg, e.getExpectedValue()));    //store the average value output in the result
                Glogger.debug("Example: " + e + "Weight learned at the end of a minibatch: " + old + " -> " + b.valAvg);
            } else if (Global.getGrounding() == Global.groundingSet.max) {
                double old = b.valMax;
                b.valMax = Evaluator.evaluateMax(b);  //forward propagation
                results.add(new Result(b.valMax, e.getExpectedValue()));    //store the average value output in the result
                Glogger.debug("Example: " + e + "Weight learned at the end of a minibatch: " + old + " -> " + b.valMax);
            } else {
                throw new AssertionError();
            }
        }

        Glogger.LogTrain("bp_step", new Double[]{results.getLearningError(), results.getDispersion(), results.getMajorityClass(), results.getThreshold()});
        Glogger.process("Training error = " + results.getLearningError() + " (maj: " + results.getMajorityClass() + ")" + " (disp: " + results.getDispersion() + ")");

    }

    private void reGround(List<Sample> roundStore, Network net) {
        results.clear();
        ForwardChecker.exnum = 0;
        for (Sample roundElement : roundStore) {
            Example e = roundElement.getExample();
            Ball b = Grounder.solve(net.last, e);    // resubstitution for every example
            ParentCounter.countParents(b);
            roundElement.setBall(b);
            results.add(new Result(b.valMax, e.getExpectedValue()));
            Glogger.info("example: " + e + " , bval: " + b.valMax + ", avg: " + b.valAvg);
            Glogger.debug("Substitution:\t" + e + "->\t" + b.valMax);
            //GroundDotter.draw(b);
        }
        Glogger.LogTrain("resub", new Double[]{results.getLearningError(), results.getDispersion(), results.getMajorityClass(), results.getThreshold()});
        Glogger.process("Training error after resubstition =\t" + results.getLearningError() + " (maj: " + results.getMajorityClass() + ")" + " (th: " + results.getThreshold() + ")" + " (disp: " + results.getDispersion() + ")");

        double le = results.getLearningError();
        double th = results.getThreshold();
        double disp = results.getDispersion();
        if (Saver.isBetterThenBest(le, th, disp)) {
            Saver.save(net, le, th, disp);     //save the best network (last = output node)
        }
        //KL llast =  last;
        //Dotter.draw(last, new HashSet(llast.getRules()));
    }

    //----------------------OLD STUFF (for backward copatibility testing)-----------------------------
    public Results checkback(Network last, List<Example> examples) {

        List<Sample> roundStore = prepareGroundings(examples, last);

        Glogger.process("-------------checkBack----------------");
        for (int a = 0; a < Settings.restartCount; a++) {    //restarting the whole procedure
            Glogger.process("---------------------------------------------------------------------------------------------------------------------");
            Glogger.process("------------Restart: " + a);
            for (int x = 0; x < Settings.learningEpochs; x++) {      //learningEpochs = maximal substitution cycles for all examples
                Glogger.process("-------epochae: " + x);
                for (int i = 0; i < Settings.learningSteps; i++) {       //learningSteps = backpropagation steps
                    Results res = new Results();
                    Glogger.process("------learning step: " + i);
                    for (Sample result : roundStore) {     //for each example(result)
                        Example e = result.getExample();
                        Ball b = result.getBall();
                        double old = b.valMax;
                        //b.valMax = Evaluator.evaluateMax(b);  //forward propagation
                        //double old = b.valMax;
                        Weights w = BackpropDown.getNewWeights(b, e);  //backpropagation
                        refreshWeights(w);  //update
                        b.valMax = Evaluator.evaluateMax(b);  //forward propagation
                        res.add(new Result(b.valMax, e.getExpectedValue()));
                        Glogger.debug("Example: " + e + "\t : Weight learning: " + old + " -> " + b.valMax);
                    }   //learning errors on this fixed ground tree (found as max subst before learning)
                    Glogger.LogTrain("bp_step", new Double[]{res.getLearningError(), res.getDispersion(), res.getMajorityClass(), res.getThreshold()});
                    Glogger.process("Training error before max. subst. =\t" + res.getLearningError() + " (maj: " + res.getMajorityClass() + ")" + " (disp: " + res.getDispersion() + ")");

                    Glogger.process("-----------proper evaluation at the end of bp-step (minibatch)");
                    saveTemplate(roundStore, last);
                    Glogger.process("---<");
                }

                reGround(roundStore, last);
            }
            Glogger.LogTrain("...restart " + a);
            Invalidator.invalidate(last);       //reset all weights before restart
        }

        endTraining(roundStore, last);

        return results;
    }

    public Results checkbackAvg(Network last, List<Example> examples) {
        Glogger.process("-------------checkBackAVG----------------");
        List<Sample> roundStore = prepareGroundings(examples, last);   //this stays as with no pruning both avg and max are found
        Results res;
        Glogger.process("-------------checkBackAVG----------------");
        for (int a = 0; a < Settings.restartCount; a++) {    //restarting the whole procedure
            Glogger.process("-------------------------------------------------------------------------------------------------------------------------------");
            Glogger.process("------------Restart: " + a);
            for (int i = 0; i < Settings.learningSteps; i++) {       //learningSteps = backpropagation steps
                res = new Results();
                if (Global.isLearnDecay()) {
                    Settings.learnRate = learnDecay(i, Settings.learnRate);
                }
                Glogger.process("---learning step: " + i);
                if (Global.isSGD()) {
                    Collections.shuffle(roundStore, Global.getRg());    //stochastic gradient descend!
                }
                for (Sample result : roundStore) {     //for each example(result)
                    Example e = result.getExample();
                    Ball b = result.getBall();
                    double old = b.valAvg;

                    Glogger.debug("Example: " + e + "Weight change from last minibatch (after 1-bp over all other examples) " + old + " -> " + b.valAvg);
                    Weights w = BackpropDownAvg.getNewWeights(b, e);  //backpropagation
                    refreshWeights(w);  //update weights
                    b.valAvg = Evaluator.evaluateAvg(b);  //forward propagation
                    res.add(new Result(b.valAvg, e.getExpectedValue()));
                }
                Glogger.LogTrain("bp_step", new Double[]{res.getLearningError(), res.getDispersion(), res.getMajorityClass(), res.getThreshold()});
                Glogger.process("Training error before max. subst. =\t" + res.getLearningError() + " (maj: " + res.getMajorityClass() + ")" + " (disp: " + res.getDispersion() + ")");
            }
            saveTemplate(roundStore, last);
            Glogger.LogTrain("...restart " + a);
            Invalidator.invalidate(last);       //reset all weights before restart
        }

        endTraining(roundStore, last);

        return results;
    }

}

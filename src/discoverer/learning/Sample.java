package discoverer.learning;

import discoverer.construction.example.Example;
import discoverer.grounding.evaluation.Ball;

/**
 * one grounded network for example
 */
public class Sample {
    private Example example;
    private Ball ball;

    public Sample(Example e, Ball b) {
        example = e;
        ball = b;
    }

    public Example getExample() {
        return example;
    }

    public Ball getBall() {
        return ball;
    }

    public void setBall(Ball b) {
        ball = b;
    }
}

package discoverer.construction.network;

import discoverer.construction.network.rules.KappaRule;
import discoverer.global.Global;
import java.util.*;

/**
 * Kappa node
 */
public class Kappa extends KL {

    private List<KappaRule> rules;
    public double offset;
    public double step;
    public double deltaW;
    private Double gradient;
    private Integer id;

    public Kappa(String name) {
        super(name);
        rules = new ArrayList<KappaRule>();
        step = 0.01;
    }

    public Kappa(String name, Double d) {
        super(name);
        rules = new ArrayList<KappaRule>();
        offset = d;
        step = 0.01;
    }

    public void initOffset() {
        offset = 1/((Global.rg.nextDouble()-0.5) * Global.initKappaOffsetK * rules.size());
    }

    public double getGradient() {
        return gradient;
    }

    public boolean gradientIsNull() {
        return gradient == null;
    }

    public void setGradient(double g) {
        gradient = g;
    }

    public void eraseGradient() {
        gradient = null;
    }

    public void addRule(KappaRule kr) {
        rules.add(kr);
    }

    public void setOffset(double d) {
        offset = d;
    }

    public void setId(Integer i) {
        id = i;
    }

    public double getOffset() {
        return offset;
    }

    public boolean isElement() {
        return rules.isEmpty();
    }

    public Integer getId() {
        return id;
    }

    public boolean hasId() {
        return id != null;
    }

    public List<KappaRule> getRules() {
        return rules;
    }
}

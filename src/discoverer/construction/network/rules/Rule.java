package discoverer.construction.network.rules;

import discoverer.construction.Terminal;
import discoverer.construction.network.rules.SubKL;
import java.util.*;

/**
 * One line with rule
 */
public abstract class Rule {
    public String original;
    public Set<Terminal> unbound;
    private Terminal lastBindedVar;

    public Rule() {
        unbound = new HashSet<Terminal>();
    }

    public Terminal getLastBindedVar() {
        return lastBindedVar;
    }

    public void setLastBindedVar(Terminal term) {
        lastBindedVar = term;
    }

    /**
     * unifying of (un)binding of this Rule head's variables/terms and consumed list of variables(must be same size)
     * @param vars 
     */
    public void consumeVars(List<Terminal> vars) {
        if (vars == null || vars.size() == 0)
            return;

        SubKL head = getHead();
        for (int i = 0; i < vars.size(); i++) {
            Terminal var = head.getTerm(i);
            Terminal boundedVar = vars.get(i);
            if (boundedVar.isBind()) {
                int c = boundedVar.getBind();
                bind(var, c);
            } else {
                unbind(var);
            }
        }
    }

    /**
     * unbind all of head's Terms(Terminals)
     */
    public void unconsumeVars() {
        SubKL head = getHead();
        for (Terminal v: head.getTerms())
            unbind(v);
    }

    public void bind(Terminal var, int c) {
        var.setBind(c);
        unbound.remove(var);
    }

    public void unbind(Terminal var) {
        var.unBind();
        unbound.add(var);
    }

    //public Terminal getNextUnbound() { return unbound.iterator().next(); }
    public abstract Terminal getNextUnbound();

    public boolean isBound() { return unbound.isEmpty(); }
    protected abstract SubKL getHead();
}

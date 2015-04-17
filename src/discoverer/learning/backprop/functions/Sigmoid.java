package discoverer.learning.backprop.functions;

public class Sigmoid {
    public static final double sigmoid(double x) {
        return (1 / (1 + Math.pow(Math.E, -x)));
        //return x;   //test-mode
    }

    public static final double sigmoidDerived(double x) {
        double sx = sigmoid(x);
        return sx * (1 - sx);
    }
}
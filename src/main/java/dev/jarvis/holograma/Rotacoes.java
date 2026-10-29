package dev.jarvis.holograma;

/**
 * A conta de rotação do {@code rad}: girar um vetor em torno de um eixo do mundo e compor esse
 * giro com a rotação que a peça já tinha.
 *
 * <p>Os ângulos são de Euler na ordem {@code XYZ}, a padrão do Three.js, onde a matriz é
 * {@code Rx · Ry · Rz} — o {@code Rz} age primeiro e o {@code Rx} por último. As fórmulas de
 * {@link #matriz} e {@link #euler} são as do {@code Matrix4.makeRotationFromEuler} e do
 * {@code Euler.setFromRotationMatrix} de lá; se divergirem, a peça gira num lugar no servidor e
 * aparece em outro na tela.
 */
final class Rotacoes {

    private Rotacoes() {
    }

    /** O vetor {@code v} girado {@code graus} em torno do eixo do mundo ({@code x}, {@code y} ou {@code z}). */
    static double[] girar(char eixo, double graus, double[] v) {
        return aplicar(doEixo(eixo, graus), v);
    }

    /**
     * A rotação que resulta de aplicar, <b>depois</b> da rotação {@code euler}, um giro de
     * {@code graus} em torno do eixo do mundo: {@code R(eixo, graus) · R(euler)}.
     *
     * <p>Quando somar no ângulo dá exatamente o mesmo, soma — sai um número que dá para ler
     * ({@code [0,90,0]} em vez de {@code [180,90,180]}). Com {@code XYZ} isso vale sempre no
     * {@code x}, que é o giro de fora; no {@code y} quando não há giro em {@code x}; e no
     * {@code z} quando não há giro nem em {@code x} nem em {@code y}.
     */
    static double[] compor(char eixo, double graus, double[] euler) {
        boolean semX = euler[0] == 0, semY = euler[1] == 0;
        if (eixo == 'x') {
            return new double[] { euler[0] + graus, euler[1], euler[2] };
        }
        if (eixo == 'y' && semX) {
            return new double[] { euler[0], euler[1] + graus, euler[2] };
        }
        if (eixo == 'z' && semX && semY) {
            return new double[] { euler[0], euler[1], euler[2] + graus };
        }
        return euler(multiplicar(doEixo(eixo, graus), matriz(euler)));
    }

    static double[][] matriz(double[] eulerGraus) {
        double x = Math.toRadians(eulerGraus[0]), y = Math.toRadians(eulerGraus[1]),
                z = Math.toRadians(eulerGraus[2]);
        double a = Math.cos(x), b = Math.sin(x), c = Math.cos(y), d = Math.sin(y),
                e = Math.cos(z), f = Math.sin(z);
        double ae = a * e, af = a * f, be = b * e, bf = b * f;
        return new double[][] {
                { c * e, -c * f, d },
                { af + be * d, ae - bf * d, -b * c },
                { bf - ae * d, be + af * d, a * c } };
    }

    static double[] euler(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, m[0][2])));
        double x, z;
        if (Math.abs(m[0][2]) < 0.9999999) {
            x = Math.atan2(-m[1][2], m[2][2]);
            z = Math.atan2(-m[0][1], m[0][0]);
        } else {
            x = Math.atan2(m[2][1], m[1][1]);
            z = 0;
        }
        return new double[] { Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }

    private static double[][] doEixo(char eixo, double graus) {
        double t = Math.toRadians(graus), c = Math.cos(t), s = Math.sin(t);
        return switch (eixo) {
            case 'x' -> new double[][] { { 1, 0, 0 }, { 0, c, -s }, { 0, s, c } };
            case 'z' -> new double[][] { { c, -s, 0 }, { s, c, 0 }, { 0, 0, 1 } };
            default -> new double[][] { { c, 0, s }, { 0, 1, 0 }, { -s, 0, c } };
        };
    }

    private static double[][] multiplicar(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    private static double[] aplicar(double[][] m, double[] v) {
        return new double[] {
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] };
    }
}

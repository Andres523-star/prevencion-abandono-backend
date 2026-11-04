package co.edu.retencion.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Construye en la prueba un modelo ONNX mínimo (regresión logística: MatMul + Add + Sigmoid) sin
 * depender de Python: escribe el protobuf {@code ModelProto} a mano. Sirve para probar MlScorer y
 * el registro de modelos con ONNX Runtime real.
 */
public final class OnnxTestModels {

    private OnnxTestModels() {
    }

    /**
     * @param weights peso por variable (en el orden de la ficha)
     * @param bias    sesgo
     * @param output  nombre de la salida ({@code [N,1]} con la probabilidad)
     */
    public static byte[] logistic(float[] weights, float bias, String output) {
        int f = weights.length;
        byte[] graph = concat(
                field(1, node("MatMul", new String[] {"features", "W"}, "xw")),
                field(1, node("Add", new String[] {"xw", "B"}, "logit")),
                field(1, node("Sigmoid", new String[] {"logit"}, output)),
                field(2, utf8("logistica")),
                field(5, tensor("W", new long[] {f, 1}, weights)),
                field(5, tensor("B", new long[] {1}, new float[] {bias})),
                field(11, valueInfo("features", f)),
                field(12, valueInfo(output, 1)));
        byte[] opset = concat(field(1, utf8("")), varintField(2, 13));
        return concat(varintField(1, 8), field(2, utf8("pruebas")), field(7, graph), field(8, opset));
    }

    private static byte[] node(String op, String[] inputs, String output) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String in : inputs) {
            out.writeBytes(field(1, utf8(in)));
        }
        out.writeBytes(field(2, utf8(output)));
        out.writeBytes(field(3, utf8(op + "_" + output)));
        out.writeBytes(field(4, utf8(op)));
        return out.toByteArray();
    }

    private static byte[] tensor(String name, long[] dims, float[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long d : dims) {
            out.writeBytes(varintField(1, d));
        }
        out.writeBytes(varintField(2, 1)); // FLOAT
        ByteBuffer floats = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            floats.putFloat(v);
        }
        out.writeBytes(field(4, floats.array()));
        out.writeBytes(field(8, utf8(name)));
        return out.toByteArray();
    }

    /** Tensor float de forma [N, cols] (N simbólica). */
    private static byte[] valueInfo(String name, int cols) {
        byte[] dimN = field(1, field(2, utf8("N")));
        byte[] dimC = field(1, varintField(1, cols));
        byte[] shape = concat(dimN, dimC);
        byte[] tensorType = concat(varintField(1, 1), field(2, shape));
        byte[] type = field(1, tensorType);
        return concat(field(1, utf8(name)), field(2, type));
    }

    private static byte[] field(int number, byte[] payload) {
        return concat(varint((long) number << 3 | 2), varint(payload.length), payload);
    }

    private static byte[] varintField(int number, long value) {
        return concat(varint((long) number << 3), varint(value));
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}

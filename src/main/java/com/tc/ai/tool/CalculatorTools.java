package com.tc.ai.tool;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTools {

    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final int DIVISION_SCALE = 8;

    @Tool(
            name = "calculate",
            description = "Calculate a result for two numbers using one operation. Supported operations are add, subtract, multiply, divide, and power."
    )
    public CalculationResult calculate(
            @ToolParam(description = "The left number in the calculation") BigDecimal left,
            @ToolParam(description = "The operation to apply: add, subtract, multiply, divide, or power") String operation,
            @ToolParam(description = "The right number in the calculation") BigDecimal right
    ) {
        String normalizedOperation = normalizeOperation(operation);
        BigDecimal result = switch (normalizedOperation) {
            case "add" -> left.add(right, MATH_CONTEXT);
            case "subtract" -> left.subtract(right, MATH_CONTEXT);
            case "multiply" -> left.multiply(right, MATH_CONTEXT);
            case "divide" -> divide(left, right);
            case "power" -> power(left, right);
            default -> throw new IllegalArgumentException("unsupported operation: " + operation);
        };

        return new CalculationResult(
                format(left),
                normalizedOperation,
                format(right),
                format(result)
        );
    }

    private String normalizeOperation(String operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }

        return switch (operation.trim().toLowerCase()) {
            case "+", "add", "plus" -> "add";
            case "-", "subtract", "minus" -> "subtract";
            case "*", "x", "multiply", "times" -> "multiply";
            case "/", "divide", "divided_by" -> "divide";
            case "^", "pow", "power" -> "power";
            default -> throw new IllegalArgumentException("unsupported operation: " + operation);
        };
    }

    private BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (right.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("division by zero is not allowed");
        }
        return left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal power(BigDecimal left, BigDecimal right) {
        try {
            return left.pow(right.intValueExact(), MATH_CONTEXT);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("power only supports integer exponents", ex);
        }
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record CalculationResult(
            String left,
            String operation,
            String right,
            String result
    ) {
    }
}

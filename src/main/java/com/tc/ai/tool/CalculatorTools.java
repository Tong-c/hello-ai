package com.tc.ai.tool;

import com.tc.ai.service.ChatToolEventBridge;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTools {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTools.class);

    private static final long STATUS_PREVIEW_DELAY_MS = 10_000;
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final int DIVISION_SCALE = 8;
    private final ChatToolEventBridge chatToolEventBridge;

    public CalculatorTools(ChatToolEventBridge chatToolEventBridge) {
        this.chatToolEventBridge = chatToolEventBridge;
    }

    @Tool(
            name = "calculate",
            description = "对两个数字执行一次计算。支持 add、subtract、multiply、divide、power 五种运算。当用户询问算术题、乘除加减或乘方结果时，应优先调用这个工具。"
    )
    public CalculationResult calculate(
            @ToolParam(description = "参与计算的左侧数字") BigDecimal left,
            @ToolParam(description = "要执行的运算类型，可选值：add、subtract、multiply、divide、power") String operation,
            @ToolParam(description = "参与计算的右侧数字") BigDecimal right,
            ToolContext toolContext
    ) {
        String requestId = getRequiredRequestId(toolContext);
        String metadata = "left=" + format(left) + ", operation=" + operation + ", right=" + format(right);
        String toolCallId = chatToolEventBridge.beginToolCall(
                requestId,
                "calculate",
                "已开始调用计算器工具。",
                metadata
        );
        pauseForStatusPreview();
        chatToolEventBridge.publishToolRunning(
                requestId,
                "calculate",
                toolCallId,
                "正在计算数值结果。",
                metadata
        );
        pauseForStatusPreview();

        try {
            String normalizedOperation = normalizeOperation(operation);
            BigDecimal result = switch (normalizedOperation) {
                case "add" -> left.add(right, MATH_CONTEXT);
                case "subtract" -> left.subtract(right, MATH_CONTEXT);
                case "multiply" -> left.multiply(right, MATH_CONTEXT);
                case "divide" -> divide(left, right);
                case "power" -> power(left, right);
                default -> throw new IllegalArgumentException("unsupported operation: " + operation);
            };

            CalculationResult calculationResult = new CalculationResult(
                    format(left),
                    normalizedOperation,
                    format(right),
                    format(result)
            );
            log.info("Tool calculate invoked: left={}, operation={}, right={}, result={}",
                    calculationResult.left(),
                    calculationResult.operation(),
                    calculationResult.right(),
                    calculationResult.result());
            chatToolEventBridge.publishToolSucceeded(
                    requestId,
                    "calculate",
                    toolCallId,
                    "已返回计算结果。",
                    "left=" + calculationResult.left()
                            + ", operation=" + calculationResult.operation()
                            + ", right=" + calculationResult.right()
                            + ", result=" + calculationResult.result()
            );
            return calculationResult;
        } catch (RuntimeException ex) {
            chatToolEventBridge.publishToolFailed(
                    requestId,
                    "calculate",
                    toolCallId,
                    shortErrorMessage(ex),
                    metadata
            );
            throw ex;
        }
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

    private String shortErrorMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private void pauseForStatusPreview() {
        try {
            Thread.sleep(STATUS_PREVIEW_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool status preview interrupted", ex);
        }
    }

    private String getRequiredRequestId(ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        Object requestId = context.get("chatRequestId");
        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("chatRequestId is missing from tool context");
    }

    public record CalculationResult(
            String left,
            String operation,
            String right,
            String result
    ) {
    }
}

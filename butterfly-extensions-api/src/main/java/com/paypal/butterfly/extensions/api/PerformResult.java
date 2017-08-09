package com.paypal.butterfly.extensions.api;

/**
 * The "perform" result of a {@link TransformationUtility}
 *
 * @author facarvalho
 */
public class PerformResult extends Result<TransformationUtility, PerformResult, PerformResult.Type> {

    public enum Type {
        
        // The result type is defined based on the utility execution result type
        EXECUTION_RESULT,

        // The TU has a condition associated with it (executeIf) but that condition resulted in false
        SKIPPED_CONDITION,

        // The TU depends on one or more TUs, and at least one of them didn't result in SUCCESS
        SKIPPED_DEPENDENCY,

        // The TU failed, but not because of its utility execution itself, but because of an internal reason
        // For example, when a TransformationUtilityException is thrown because the absolute file
        // the TU should execute against could not be resolved during transformation time
        ERROR
    
    }

    // The utility execution result, in case
    // type is EXECUTION_RESULT
    private ExecutionResult executionResult = null;

    /*
     * This means the utility has been executed,
     * and the result type is defined based on the utility execution result type.
     *
     * @param executionResult the utility execution result
     */
    private PerformResult(TransformationUtility transformationUtility, ExecutionResult executionResult) {
        super(transformationUtility, Type.EXECUTION_RESULT);
        if(executionResult == null) {
            throw new IllegalArgumentException("Execution result cannot be blank");
        }
        setDetails(executionResult.getDetails());
        this.executionResult = executionResult;
    }

    private PerformResult(TransformationUtility transformationUtility, Type type) {
        super(transformationUtility, type);
    }

    private PerformResult(TransformationUtility transformationUtility, Type type, String details) {
        super(transformationUtility, type);
        setDetails(details);
    }

    /**
     * This means the utility has been executed,
     * and the result type is defined based on the utility execution result type.
     *
     * @param executionResult the utility execution result
     */
    public static PerformResult executionResult(TransformationUtility transformationUtility, ExecutionResult executionResult) {
        return new PerformResult(transformationUtility, executionResult);
    }

    /**
     * This means the utility has not been executed
     * because its pre-requisite condition is not true
     */
    public static PerformResult skippedCondition(TransformationUtility transformationUtility, String details) {
        PerformResult result = new PerformResult(transformationUtility, Type.SKIPPED_CONDITION, details);

        // TODO should it hold the name of the transformation context attribute separately from the details?
        // Maybe not, because the utility itself has that already

        return result;
    }

    /**
     * This means the utility has not been executed because one or more
     * of its dependencies "failed". See {@link TransformationUtility#dependsOn(String...)}
     * for the dependency failure criteria definition
     */
    public static PerformResult skippedDependency(TransformationUtility transformationUtility, String details) {
        PerformResult result = new PerformResult(transformationUtility, Type.SKIPPED_DEPENDENCY, details);

        // TODO should it hold the name(s) of the dependent transformation utility(s) separately from the details?
        // Maybe not, because the utility itself has that already

        return result;
    }

    /**
     * This means The TU failed, but not because of its utility execution itself, but because of an internal reason.
     * For example, when a TransformationOperationException is thrown because the absolute file the TU should execute
     * against could not be resolved during transformation time
     */
    public static PerformResult error(TransformationUtility transformationUtility, Exception exception, String details) {
        PerformResult result = new PerformResult(transformationUtility, Type.ERROR, details);
        result.setException(exception);
        return result;
    }

    /**
     * This means The TU failed, but not because of its utility execution itself, but because of an internal reason.
     * For example, when a TransformationOperationException is thrown because the absolute file the TU should execute
     * against could not be resolved during transformation time
     */
    public static PerformResult error(TransformationUtility transformationUtility, Exception exception) {
        PerformResult result = new PerformResult(transformationUtility, Type.ERROR);
        result.setException(exception);
        return result;
    }

    @Override
    protected void changeTypeOnWarning() {
        // Nothing do be done here, since warnings do not apply to this type of result
    }

    public ExecutionResult getExecutionResult() {
        return executionResult;
    }

    @Override
    protected boolean isExceptionType() {
        switch (getType()) {
            case SKIPPED_CONDITION:
            case SKIPPED_DEPENDENCY:
                return false;
            case ERROR:
                return true;
            case EXECUTION_RESULT:
            default:
                return executionResult.isExceptionType();
        }
    }

    @Override
    protected boolean dependencyFailureCheck() {
        switch (getType()) {
            case SKIPPED_CONDITION:
            case SKIPPED_DEPENDENCY:
            case ERROR:
                return true;
            case EXECUTION_RESULT:
            default:
                return executionResult.dependencyFailureCheck();
        }
    }

}

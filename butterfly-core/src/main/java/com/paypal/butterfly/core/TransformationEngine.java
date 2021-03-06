package com.paypal.butterfly.core;

import com.paypal.butterfly.core.exception.InternalException;
import com.paypal.butterfly.extensions.api.*;
import com.paypal.butterfly.extensions.api.exception.TransformationUtilityException;
import com.paypal.butterfly.extensions.api.upgrade.UpgradePath;
import com.paypal.butterfly.extensions.api.upgrade.UpgradeStep;
import com.paypal.butterfly.extensions.api.utilities.ManualInstruction;
import com.paypal.butterfly.extensions.api.utilities.ManualInstructionRecord;
import com.paypal.butterfly.facade.Configuration;
import com.paypal.butterfly.facade.TransformationResult;
import com.paypal.butterfly.facade.exception.TransformationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The transformation engine in charge of
 * applying transformations
 *
 * @author facarvalho
 */
@Component
public class TransformationEngine {

    private static final Logger logger = LoggerFactory.getLogger(TransformationEngine.class);

    // This is used to create a timestamp to be applied as suffix in the transformed application folder
    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
    private static final String ORDER_FORMAT = "%s.%d";

    private Collection<TransformationListener> transformationListeners;

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void setupListeners() {
        Map<String, TransformationListener> beans = applicationContext.getBeansOfType(TransformationListener.class);
        transformationListeners = beans.values();
    }

    /**
     * Perform an application transformation based on the specified {@link Transformation}
     * object
     *
     * @param transformation the transformation object
     * @throws TransformationException if the transformation is aborted for any reason
     */
    public TransformationResult perform(Transformation transformation) throws TransformationException {
        if(logger.isDebugEnabled()) {
            logger.debug("Requested transformation: {}", transformation);
        }

        File transformedAppFolder = prepareOutputFolder(transformation);
        List<TransformationContextImpl> transformationContexts = new ArrayList<>();

        try {
            TransformationResult transformationResult = perform(transformedAppFolder, transformation, transformationContexts);
            postTransformationNotification(transformation, transformationContexts);

            return transformationResult;
        } catch (InternalTransformationException e) {
            TransformationContextImpl abortedTransformationContext = e.getTransformationContext();
            if (abortedTransformationContext != null) {
                transformationContexts.add(abortedTransformationContext);
            }
            postTransformationAbortionNotification(transformation, transformationContexts);

            throw e;
        }
    }

    /*
     * Notify transformation listeners about an successfully completed transformation
     */
    private void postTransformationNotification(Transformation transformation, List<TransformationContextImpl> transformationContexts) {
        List<TransformationContextImpl> transformationContextsUnmodifiableList = getTransformationContextsReadonlyList(transformationContexts);
        for (TransformationListener listener : transformationListeners) {
            listener.postTransformation(transformation, transformationContextsUnmodifiableList);
        }
    }

    /*
     * Notify transformation listeners about an aborted transformation
     */
    private void postTransformationAbortionNotification(Transformation transformation, List<TransformationContextImpl> transformationContexts) {
        List<TransformationContextImpl> transformationContextsUnmodifiableList = getTransformationContextsReadonlyList(transformationContexts);
        for (TransformationListener listener : transformationListeners) {
            listener.postTransformationAbort(transformation, transformationContextsUnmodifiableList);
        }
    }

    /*
     * Returns a list of transformation context objects to be passed to transformation listeners as part of a notification event.
     * Listeners are not suppose to modify these transformation context objects, and that is why the need of this auxiliary method.
     */
    private List<TransformationContextImpl> getTransformationContextsReadonlyList(List<TransformationContextImpl> transformationContexts) {
        // FIXME
        // It would be better to create a new list with read-only DTOs from TransformationContextImpl objects,
        // to then make it unmodifiable, and send to listeners. Otherwise listeners my change the context
        // objects, which they are not supposed to to. Right now this implementation is not preventing listeners to
        // modify transformation context objects.
        return Collections.unmodifiableList(transformationContexts);
    }

    private TransformationResult perform(File transformedAppFolder, Transformation transformation, List<TransformationContextImpl> transformationContexts) throws InternalTransformationException {
        if (transformation instanceof UpgradePathTransformation) {
            UpgradePath upgradePath = ((UpgradePathTransformation) transformation).getUpgradePath();
            perform(upgradePath, transformedAppFolder, transformationContexts);
        } else if (transformation instanceof TemplateTransformation) {
            TransformationTemplate template = ((TemplateTransformation) transformation).getTemplate();
            TransformationContextImpl transformationContext = perform(template, transformedAppFolder, null);
            transformationContexts.add(transformationContext);
        } else {
            throw new InternalTransformationException("Transformation type not recognized", null);
        }

        TransformationResult transformationResult = new TransformationResultImpl(transformation, transformedAppFolder);

        return transformationResult;
    }

    /*
     * Upgrade the application based on an upgrade path (from an original version to a target version)
     */
    private void perform(UpgradePath upgradePath, File transformedAppFolder, List<TransformationContextImpl> transformationContexts) throws InternalTransformationException {
        logger.info("");
        logger.info("====================================================================================================================================");
        logger.info("\tUpgrade path from version {} to version {}", upgradePath.getOriginalVersion(), upgradePath.getUpgradeVersion());

        UpgradeStep upgradeStep;
        TransformationContextImpl previousContext = null;
        while (upgradePath.hasNext()) {
            upgradeStep = upgradePath.next();

            logger.info("");
            logger.info("====================================================================================================================================");
            logger.info("\tUpgrade step");
            logger.info("\t\t* from version: {}", upgradeStep.getCurrentVersion());
            logger.info("\t\t* to version: {}", upgradeStep.getNextVersion());

            // The context passed to this method call is not the same as the one returned,
            // although the variable holding them is the same
            previousContext = perform(upgradeStep, transformedAppFolder, previousContext);
            transformationContexts.add(previousContext);
        }
    }

    /*
     * Transform the application based on a single transformation template. Notice that this transformation
     * template can also be an upgrade step
     */
    private TransformationContextImpl perform(TransformationTemplate template, File transformedAppFolder, TransformationContextImpl previousTransformationContext) throws InternalTransformationException {
        logger.info("====================================================================================================================================");
        logger.info("Beginning transformation");

        TransformationContextImpl transformationContext = perform(template, template.getUtilities(), transformedAppFolder, previousTransformationContext);

        logger.info("Transformation has been completed");

        return transformationContext;
    }

    /*
     * Performs a list of transformation utilities against an application.
     * Notice that any of those utilities can be operations
     */
    private TransformationContextImpl perform(TransformationTemplate template, List<TransformationUtility> utilities, File transformedAppFolder, TransformationContextImpl previousTransformationContext) throws InternalTransformationException {
        int operationsExecutionOrder = 1;
        TransformationContextImpl transformationContext = TransformationContextImpl.getTransformationContext(previousTransformationContext);
        transformationContext.setTransformationTemplate(template);

        try {
            TransformationUtility utility;
            for(Object transformationUtilityObj: utilities) {
                utility = (TransformationUtility) transformationUtilityObj;
                perform(utility, transformedAppFolder, transformationContext, String.valueOf(operationsExecutionOrder));
                if (utility instanceof TransformationOperation || utility instanceof TransformationUtilityParent) {
                    operationsExecutionOrder++;
                }
            }
        } catch (TransformationException e) {
            // TODO save exception and abortion description into the transformationContext
            throw new InternalTransformationException(e, transformationContext);
        }

        return transformationContext;
    }

    /*
     * Perform a condition against multiple files
     */
    private PerformResult perform(MultipleConditions utility, Set<File> files, File transformedAppFolder, TransformationContextImpl transformationContext) throws TransformationException {

        UtilityCondition condition;
        boolean allMode = utility.getMode().equals(MultipleConditions.Mode.ALL);
        boolean result = false;

        for (File file : files) {
            condition = utility.newConditionInstance(transformedAppFolder, file);

            PerformResult innerPerformResult = condition.perform(transformedAppFolder, transformationContext);
            processUtilityExecutionResult(condition, innerPerformResult, transformationContext);

            if(innerPerformResult.getType().equals(PerformResult.Type.EXECUTION_RESULT) &&
                    (innerPerformResult.getExecutionResult().getType().equals(TUExecutionResult.Type.VALUE)
                    || innerPerformResult.getExecutionResult().getType().equals(TUExecutionResult.Type.WARNING))) {
                result = (boolean) ((TUExecutionResult) innerPerformResult.getExecutionResult()).getValue();
                if (!result && allMode || result && !allMode) {
                    break;
                }
            } else {
                Exception innerException;
                if (innerPerformResult.getType().equals(PerformResult.Type.ERROR)) {
                    innerException = innerPerformResult.getException();
                } else {
                    innerException = innerPerformResult.getExecutionResult().getException();
                }
                String exceptionMessage = String.format("Multiple utility condition %s execution failed when evaluating condition %s against file %s", utility.getName(), condition.getName(), file.getAbsolutePath());
                TransformationUtilityException outerException = new TransformationUtilityException(exceptionMessage, innerException);
                TUExecutionResult multipleExecutionResult = TUExecutionResult.error(utility, outerException);
                return PerformResult.executionResult(utility, multipleExecutionResult);
            }
        }

        TUExecutionResult multipleExecutionResult = TUExecutionResult.value(utility, result);
        return PerformResult.executionResult(utility, multipleExecutionResult);
    }

    /*
     * Perform a filter in a list of files based on a condition
     */
    private PerformResult perform(FilterFiles utility, Set<File> files, File transformedAppFolder, TransformationContextImpl transformationContext) throws TransformationException {

        SingleCondition condition;
        boolean conditionResult;
        List<File> subList = new ArrayList<>();

        for (File file : files) {
            condition = utility.newConditionInstance(transformedAppFolder, file);

            PerformResult innerPerformResult = condition.perform(transformedAppFolder, transformationContext);

            // FIXME
            // Check here the PerformResult type. It should be ExecutionResult, but if it is not, then decide what to do
            // (what happens when the execution of a regular condition fails? does it return false? or does it do something else?),
            // but definition `processUtilityExecutionResult` cannot be called, otherwise it will result in a NPE!

            processUtilityExecutionResult(condition, innerPerformResult, transformationContext);

            if(innerPerformResult.getType().equals(PerformResult.Type.EXECUTION_RESULT) &&
                    (innerPerformResult.getExecutionResult().getType().equals(TUExecutionResult.Type.VALUE)
                            || innerPerformResult.getExecutionResult().getType().equals(TUExecutionResult.Type.WARNING))) {
                conditionResult = (boolean) ((TUExecutionResult) innerPerformResult.getExecutionResult()).getValue();
                if (conditionResult) {
                    subList.add(file);
                }
            } else {
                Exception innerException;
                if (innerPerformResult.getType().equals(PerformResult.Type.ERROR)) {
                    innerException = innerPerformResult.getException();
                } else {
                    innerException = innerPerformResult.getExecutionResult().getException();
                }
                String exceptionMessage = String.format("FilterFiles %s failed when evaluating condition %s against file %s", utility.getName(), condition.getName(), file.getAbsolutePath());
                TransformationUtilityException outerException = new TransformationUtilityException(exceptionMessage, innerException);
                TUExecutionResult multipleExecutionResult = TUExecutionResult.error(utility, outerException);
                return PerformResult.executionResult(utility, multipleExecutionResult);
            }
        }

        TUExecutionResult filterFilesExecutionResult = TUExecutionResult.value(utility, subList);
        return PerformResult.executionResult(utility, filterFilesExecutionResult);
    }

    /*
     * Perform a list of utilities in an application
     */
    private void perform(TransformationUtilityParent utilityParent, PerformResult result, File transformedAppFolder, TransformationContextImpl transformationContext, String order) throws TransformationException {
        TUExecutionResult.Type executionResultType = (TUExecutionResult.Type) result.getExecutionResult().getType();
        if(!executionResultType.equals(TUExecutionResult.Type.VALUE)) {
            processUtilityExecutionResult((TransformationUtility) utilityParent, result, transformationContext);
            return;
        }

        // TODO print number of \t based on depth of parents
        logger.info("\t{}\t - Executing utilities parent {}", order, utilityParent.getName());

        String childOrder;
        int i = 1;
        for(TransformationUtility utility : utilityParent.getChildren()) {
            childOrder = String.format(ORDER_FORMAT, order, i);
            perform(utility, transformedAppFolder, transformationContext, childOrder);
            if (utility instanceof TransformationOperation || utility instanceof TransformationUtilityParent) {
                i++;
            }
        }
    }

    /*
     * Perform an transformation utility against an application. Notice that this utility can also be
     * actually a transformation operation
     */
    private void perform(TransformationUtility utility, File transformedAppFolder, TransformationContextImpl transformationContext, String order) throws TransformationException {
        boolean isTO = utility instanceof TransformationOperation;
        PerformResult result = null;
        try {
            result = utility.perform(transformedAppFolder, transformationContext);

            switch (result.getType()) {
                case SKIPPED_CONDITION:
                    // Same as SKIPPED_DEPENDENCY
                case SKIPPED_DEPENDENCY:
                    if (isTO || logger.isDebugEnabled()) {
                        // TODO print number of \t based on depth of parents
                        logger.debug("\t{}\t - {}", order, result.getDetails());
                    }
                    break;
                case EXECUTION_RESULT:
                    if (isTO) {
                        processOperationExecutionResult(utility, result, order, transformationContext);
                    } else {
                        TUExecutionResult executionResult = (TUExecutionResult) result.getExecutionResult();
                        Object executionValue = executionResult.getValue();

                        if(executionResult.getType().equals(TUExecutionResult.Type.ERROR)) {
                            processUtilityExecutionResult(utility, result, transformationContext);
                            break;
                        }

                        if(utility instanceof MultipleConditions) {

                            /* Executing a condition against multiple files */
                            Set<File> files = (Set<File>) executionValue;
                            result = perform((MultipleConditions) utility, files, transformedAppFolder, transformationContext);
                        } else if(utility instanceof FilterFiles) {

                            /* Execute a filter in a list of files based on a condition */
                            Set<File> files = (Set<File>) executionValue;
                            result = perform((FilterFiles) utility, files, transformedAppFolder, transformationContext);
                        }

                        processUtilityExecutionResult(utility, result, transformationContext);

                        if (utility instanceof TransformationUtilityLoop) {

                            /* Executing loops of utilities */
                            boolean iterate = executionValue instanceof Boolean && ((Boolean) executionValue).booleanValue();
                            if (iterate) {
                                TransformationUtilityLoop utilityLoop = (TransformationUtilityLoop) utility;
                                String newOrder = String.format("%s.%s", order, utilityLoop.getNextIteration());

                                logger.info("...........................");
                                logger.info("\t{}\t - Iteration {} loop {}", newOrder, utilityLoop.getNextIteration(), utilityLoop.getName());

                                perform(utilityLoop.run(), transformedAppFolder, transformationContext, newOrder + ".1");
                                perform(utilityLoop.iterate(), transformedAppFolder, transformationContext, order);
                            }
                        } else if(utility instanceof TransformationUtilityParent) {

                            /* Executing utilities parents */
                            perform((TransformationUtilityParent) utility, result, transformedAppFolder, transformationContext, order);
                        } else if(utility instanceof ManualInstruction) {

                            /* Adding manual instruction */
                            transformationContext.registerManualInstruction((ManualInstructionRecord) executionValue);
                        }
                    }
                    break;
                case ERROR:
                    processError(utility, result.getException(), order, transformationContext);
                    break;
                default:
                    logger.error("\t{}\t - '{}' has resulted in an unexpected perform result type {}", order, utility.getName(), result.getType().name());
                    break;
            }
        } catch (TransformationUtilityException e) {
            result = PerformResult.error(utility, e);
            processError(utility, e, order, transformationContext);
        } finally {
            if (utility.isSaveResult()) {
                // Saving the whole perform result, which is different from the value that resulted from the utility execution,
                // saved in processUtilityExecutionResult

                transformationContext.putResult(utility.getName(), result);
            }
        }
    }

    private void processError(TransformationUtility utility, Exception e, String order, TransformationContextImpl transformationContext) throws TransformationException {
        if (utility.abortOnFailure()) {
            logger.error("*** Transformation will be aborted due to failure in {} ***", utility.getName());
            String abortionMessage = utility.getAbortionMessage();
            if (abortionMessage != null) {
                logger.error("*** {} ***", abortionMessage);
            }
            logger.error("*** Description: {}", utility.getDescription());
            logger.error("*** Cause: {}", e.getMessage());
            logger.error("*** Exception stack trace:", e);

            String exceptionMessage = (abortionMessage != null ? abortionMessage : utility.getName() + " failed when performing transformation");
            transformationContext.transformationAborted(e, exceptionMessage, utility.getName());

            throw new TransformationException(exceptionMessage, e);
        } else {
            logger.error("\t{}\t -  '{}' has failed. See debug logs for further details. Utility name: {}", order, utility.getDescription(), utility.getName());
            if(logger.isDebugEnabled()) {
                logger.error(utility.getName() + " has failed due to the exception below", e);
            }
        }
    }

    private void processOperationExecutionResult(TransformationUtility utility, PerformResult result, String order, TransformationContextImpl transformationContext) throws TransformationException {
        TOExecutionResult executionResult = (TOExecutionResult) result.getExecutionResult();
        switch (executionResult.getType()) {
            case SUCCESS:
                // TODO print number of \t based on depth of parents
                logger.info("\t{}\t - {}", order, executionResult.getDetails());
                break;
            case NO_OP:
                // TODO print number of \t based on depth of parents
                logger.debug("\t{}\t - {}", order, executionResult.getDetails());
                break;
            case WARNING:
                processExecutionResultWarningType(utility, executionResult, order);
                break;
            case ERROR:
                processError(utility, executionResult.getException(), order, transformationContext);
                break;
            default:
                processExecutionResultUnknownType(utility, executionResult, order);
                break;
        }
    }

    private void processUtilityExecutionResult(TransformationUtility utility, PerformResult result, TransformationContextImpl transformationContext) throws TransformationException {
        TUExecutionResult executionResult = (TUExecutionResult) result.getExecutionResult();
        if (utility.isSaveResult()) {
            // Saving the value that resulted from the utility execution, which is different from the whole perform result
            // object saved in the main perform method

            String key = (utility.getContextAttributeName() != null ? utility.getContextAttributeName() : utility.getName());
            transformationContext.put(key, executionResult.getValue());
        }
        switch (executionResult.getType()) {
            case NULL:
                if (utility.isSaveResult() && logger.isDebugEnabled()) {
                    logger.warn("\t-\t - {}. {} has returned NULL", utility, utility.getName());
                }
                break;
            case VALUE:
                logger.debug("\t-\t - [{}][Result: {}][Utility: {}]", StringUtils.abbreviate(utility.toString(), 240),  StringUtils.abbreviate(executionResult.getValue().toString(), 120), utility.getName());
                break;
            case WARNING:
                processExecutionResultWarningType(utility, executionResult, "-");
                break;
            case ERROR:
                processError(utility, executionResult.getException(), "-", transformationContext);
                break;
            default:
                processExecutionResultUnknownType(utility, executionResult, "-");
                break;
        }
    }

    private void processExecutionResultWarningType(TransformationUtility utility, ExecutionResult executionResult, String order) {
        logger.warn("\t{}\t -  '{}' has successfully been executed, but it has warnings, see debug logs for further details. Utility name: {}", order, utility.getDescription(), utility.getName());
        if (logger.isDebugEnabled()) {
            if (executionResult.getWarnings().size() == 0) {
                logger.warn("\t\t\t * Warning message: {}", executionResult.getDetails());
            } else {
                logger.warn("\t\t\t * Execution details: {}", executionResult.getDetails());
                logger.warn("\t\t\t * Warnings:");
                for (Object warning : executionResult.getWarnings()) {
                    String message = String.format("\t\t\t\t - %s: %s", warning.getClass().getName(), ((Exception) warning).getMessage());
                    logger.warn(message, warning);
                }
            }
        }
    }

    private void processExecutionResultUnknownType(TransformationUtility utility, ExecutionResult executionResult, String order) {
        logger.error("\t{}\t - '{}' has resulted in an unexpected execution result type {}", order, utility.getName(), executionResult.getType());
    }

    private File prepareOutputFolder(Transformation transformation) {
        logger.debug("Preparing output folder");

        Application application =  transformation.getApplication();
        Configuration configuration =  transformation.getConfiguration();

        logger.info("Original application folder:\t\t" + application.getFolder());

        File originalAppParent = application.getFolder().getParentFile();
        if (originalAppParent == null) {
            originalAppParent = new File(System.getProperty("user.dir"));
        }

        String transformedAppFolderName = application.getFolder().getName() + "-transformed-" + simpleDateFormat.format(new Date());

        File transformedAppFolder;

        if(configuration.getOutputFolder() != null) {
            if(!configuration.getOutputFolder().exists()) {
                throw new IllegalArgumentException("Invalid output folder (" + configuration.getOutputFolder() + ")");
            }
            transformedAppFolder = new File(configuration.getOutputFolder().getAbsolutePath() + File.separator + transformedAppFolderName);
        } else {
            transformedAppFolder = new File(originalAppParent.getAbsolutePath() + File.separator + transformedAppFolderName);
        }

        logger.info("Transformed application folder:\t" + transformedAppFolder);

        transformation.setTransformedApplicationLocation(transformedAppFolder);

        boolean bDirCreated = transformedAppFolder.mkdir();
        if(bDirCreated){
            try {
                FileUtils.copyDirectory(application.getFolder(), transformedAppFolder);
            } catch (IOException e) {
                String exceptionMessage = String.format(
                        "An error occurred when preparing the transformed application folder (%s). Check also if the original application folder (%s) is valid",
                        transformedAppFolder, application.getFolder());
                logger.error(exceptionMessage, e);
                throw new InternalException(exceptionMessage, e);
            }
            logger.debug("Transformed application folder is prepared");
        }else{
            String exceptionMessage = String.format("Transformed application folder (%s) could not be created", transformedAppFolder);
            InternalException ie  = new InternalException(exceptionMessage);
            logger.error(exceptionMessage, ie);
            throw ie;
        }
        return transformedAppFolder;
    }

}

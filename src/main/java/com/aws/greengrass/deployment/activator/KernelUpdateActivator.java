/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.lifecyclemanager.exceptions.DirectoryValidationException;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;

/**
 * Activation and rollback of Kernel update deployments.
 */
public class KernelUpdateActivator extends DeploymentActivator {
    private final BootstrapManager bootstrapManager;
    private final KernelAlternatives kernelAlternatives;

    /**
     * Constructor of KernelUpdateActivator.
     *
     * @param kernel           Kernel instance
     * @param bootstrapManager BootstrapManager instance
     */
    @Inject
    public KernelUpdateActivator(Kernel kernel, BootstrapManager bootstrapManager) {
        super(kernel);
        this.bootstrapManager = bootstrapManager;
        this.kernelAlternatives = kernel.getContext().get(KernelAlternatives.class);
    }

    @Override
    public void activate(Map<String, Object> newConfig, Deployment deployment,
                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        if (!takeConfigSnapshot(totallyCompleteFuture)) {
            return;
        }
        try {
            kernelAlternatives.validateLaunchDirSetupVerbose();
        } catch (DirectoryValidationException e) {
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DeploymentException("Unable to process deployment. Greengrass launch directory"
                                    + " is not set up or Greengrass is not set up as a system service", e)));
            return;
        } catch (DeploymentException e) {
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return;
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        KernelLifecycle lifecycle = kernel.getContext().get(KernelLifecycle.class);
        // Preserve tlog state before launch directory is updated to reflect ongoing deployment.
        // Wait for all services to close.
        lifecycle.softShutdown(30);

        updateConfiguration(deploymentDocument.getTimestamp(), newConfig);

        Path bootstrapTaskFilePath;
        try {
            bootstrapTaskFilePath = deploymentDirectoryManager.getBootstrapTaskFilePath();
            deploymentDirectoryManager.takeConfigSnapshot(deploymentDirectoryManager.getTargetConfigFilePath());
            bootstrapManager.persistBootstrapTaskList(bootstrapTaskFilePath);
            kernelAlternatives.prepareBootstrap(deploymentDocument.getDeploymentId());
        } catch (IOException e) {
            // TODO: better handling of error codes for different IO operations
            rollback(deployment, e, false);
            lifecycle.startupAllServices();

            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DeploymentException("Unable to process deployment. Greengrass could not prepare for"
                                    + " bootstrap.", e)));
            return;
        }

        try {
            int exitCode = bootstrapManager.executeAllBootstrapTasksSequentially(bootstrapTaskFilePath);
            if (!bootstrapManager.hasNext()) {
                logger.atInfo().log("Completed all bootstrap tasks. Continue to activate deployment changes");
            }
            // If exitCode is 0, which happens when all bootstrap tasks are completed, restart in new launch
            // directories and verify handover is complete. As a result, exit code 0 is treated as 100 here.
            logger.atInfo().log((exitCode == REQUEST_REBOOT ? "device reboot" : "Nucleus restart")
                    + " requested to complete bootstrap task");

            kernel.shutdown(30, exitCode == REQUEST_REBOOT ? REQUEST_REBOOT : REQUEST_RESTART);
        } catch (ServiceUpdateException | IOException e) {
            rollback(deployment, e, true);
        }
    }

    void rollback(Deployment deployment, Throwable failureCause, boolean requiresRestart) {
        logger.atInfo(MERGE_CONFIG_EVENT_KEY, failureCause)
                .kv(DEPLOYMENT_ID_LOG_KEY, deployment.getGreengrassDeploymentId())
                .log("Rolling back failed deployment");

        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(failureCause);
        deployment.setErrorStack(errorReport.getLeft());
        deployment.setErrorTypes(errorReport.getRight());
        deployment.setStageDetails(Utils.generateFailureMessage(failureCause));

        // Persist deployment metadata only if restart is required
        if (requiresRestart) {
            final boolean bootstrapOnRollbackRequired = kernelAlternatives.prepareBootstrapOnRollbackIfNeeded(
                    kernel.getContext(), deploymentDirectoryManager, bootstrapManager);

            deployment.setDeploymentStage(bootstrapOnRollbackRequired ? ROLLBACK_BOOTSTRAP : KERNEL_ROLLBACK);
        }

        try {
            deploymentDirectoryManager.writeDeploymentMetadata(deployment);
        } catch (IOException ioException) {
            logger.atError().setCause(ioException).log("Failed to persist deployment details");
        }
        try {
            kernelAlternatives.prepareRollback();
        } catch (IOException e) {
            logger.atError().setCause(e).log("Failed to set up rollback directory");
        }

        // Restart Kernel regardless and rely on loader orchestration
        if (requiresRestart) {
            kernel.shutdown(30, REQUEST_RESTART);
        }
    }
}

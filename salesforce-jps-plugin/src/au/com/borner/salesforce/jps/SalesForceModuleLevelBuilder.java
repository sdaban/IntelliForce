/*
 * Copyright 2014 Mark Borner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package au.com.borner.salesforce.jps;

import au.com.borner.salesforce.client.rest.ConnectionManager;
import au.com.borner.salesforce.client.rest.InstanceCredentials;
import au.com.borner.salesforce.client.rest.ToolingRestClient;
import au.com.borner.salesforce.client.rest.domain.*;
import au.com.borner.salesforce.client.wsc.SoapClient;
import au.com.borner.salesforce.util.FileUtilities;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.FileProcessor;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * The Salesforce Module Level Builder (aka Compiler)
 *
 * @author mark
 */
public class SalesForceModuleLevelBuilder extends ModuleLevelBuilder {

    private static final String EMPTY = "";

    public SalesForceModuleLevelBuilder() {
        super(BuilderCategory.TRANSLATOR);
    }

    @Override
    public ExitCode build(final CompileContext context,
                          ModuleChunk chunk,
                          DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
                          OutputConsumer outputConsumer) throws ProjectBuildException, IOException {

        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Starting Salesforce Compiler"));
        SalesForceProjectSettings projectSettings = SalesForceProjectSettings.getSettings(context.getProjectDescriptor().getProject());

        // Step 1: login to Salesforce
        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Logging into Salesforce"));
        InstanceCredentials instanceCredentials = new InstanceCredentials("Compile");
        instanceCredentials.setUsername(System.getProperty("username"));
        instanceCredentials.setPassword(System.getProperty("password"));
        instanceCredentials.setSecurityToken(System.getProperty("securityToken"));
        instanceCredentials.setEnvironment(System.getProperty("environment"));
        SoapClient soapClient = new SoapClient();
        soapClient.login(instanceCredentials, Boolean.getBoolean(System.getProperty("traceMessages")));
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.setSessionDetails(soapClient.getSessionId(), soapClient.getServiceHost());
        final ToolingRestClient toolingRestClient = new ToolingRestClient(connectionManager);

        // Step 2: create Metadata container
        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Creating Metadata Container"));
        String containerId = UUID.randomUUID().toString().replaceAll("-", "");
        final MetadataContainer metadataContainer = toolingRestClient.createSObject(new MetadataContainer(containerId));

        // Step 3: upload all the modified files into the Metadata container
        final Map<String, String> fileNameToUrlMap = new HashMap<String, String>();
        dirtyFilesHolder.processDirtyFiles(new FileProcessor<JavaSourceRootDescriptor, ModuleBuildTarget>() {
            @Override
            public boolean apply(ModuleBuildTarget target, File file, JavaSourceRootDescriptor root) throws IOException {
                fileNameToUrlMap.put(FileUtilities.filenameWithoutExtension(file.getName()), file.getAbsolutePath());
                uploadSource(file, metadataContainer, context, toolingRestClient);
                return true;
            }
        });

        // Step 4: invoke the Salesforce compiler
        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Requesting Async Compile"));
        ContainerAsyncRequest containerAsyncRequest = new ContainerAsyncRequest(metadataContainer);
        containerAsyncRequest = toolingRestClient.createSObject(containerAsyncRequest);
        int retryCount = 0;
        // TODO: make these parameters configurable because large amount of source files will take longer to compile!
        while ((containerAsyncRequest.getState() == null || containerAsyncRequest.getState().equals(ContainerAsyncRequest.State.Queued)) && ++retryCount < 60) {
            try {
                Thread.sleep(5000);
            } catch (Exception e) {
                // ignore
            }
            containerAsyncRequest = toolingRestClient.getSObject(containerAsyncRequest, ContainerAsyncRequest.class);
        }

        // Step 5: check result
        if (containerAsyncRequest.getState() == null || containerAsyncRequest.getState().equals(ContainerAsyncRequest.State.Queued)) {
            context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, "Compile has timed out"));
            soapClient.logoff();
            return ExitCode.ABORT;
        }

        ExitCode exitCode = ExitCode.OK;
        switch (containerAsyncRequest.getState()) {
            case Completed:
                context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.INFO, "Compilation was successful"));
                break;
            case Error:
                context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, "A compiler error occurred " + containerAsyncRequest.getErrorMsg()));
                exitCode = ExitCode.ABORT;
                break;
            case Failed:
                List<CompilerError> compilerErrors = containerAsyncRequest.getCompilerErrors();
                if (compilerErrors.size() == 0) {
                    context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, "Compilation failed"));
                } else {
                    for (CompilerError compilerError : compilerErrors) {
                        String filePath = fileNameToUrlMap.get(compilerError.getName());
                        Pair<Integer,Integer> location = compilerError.getLocation();
                        if (location == null) {
                            context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, compilerError.getProblem(), filePath, -1, -1, -1, compilerError.getLine(), 0));
                        } else {
                            context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, compilerError.getProblem(), filePath, -1, -1, -1, location.first, location.second));
                        }
                    }
                }
                exitCode = ExitCode.ABORT;
                break;
            case Invalidated:
                context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.WARNING, "The compilation was invalidated by the server"));
                exitCode = ExitCode.CHUNK_REBUILD_REQUIRED;
                break;
            case Aborted:
                context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.WARNING, "The compilation was aborted"));
                exitCode = ExitCode.CHUNK_REBUILD_REQUIRED;
                break;
            default:
                context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, "An unknown error occurred"));
                exitCode = ExitCode.ABORT;
                break;
        }

        // Step 6: Delete metadata container and logoff
        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Deleting Metadata Container"));
        toolingRestClient.deleteSObject(metadataContainer);
        soapClient.logoff();
        return exitCode;
    }

    @Override
    public List<String> getCompilableFileExtensions() {
        return Arrays.asList("cls", "page", "trigger", "component");
    }

    @NotNull
    @Override
    public String getPresentableName() {
        return "Salesforce Compiler";
    }

    // ----------------------------------------

    public void uploadSource(@NotNull File file, @NotNull MetadataContainer metadataContainer, @NotNull CompileContext context, @NotNull ToolingRestClient toolingRestClient) {
        AbstractMetadataMember member;
        if (file.getName().endsWith(".cls")) {
            member = new ApexClassMember(metadataContainer);
        } else if (file.getName().endsWith(".trigger")) {
            member = new ApexTriggerMember(metadataContainer);
        } else if (file.getName().endsWith(".page")) {
            member = new VisualForcePageMember(metadataContainer);
        } else if (file.getName().endsWith(".component")){
            member = new VisualForceComponentMember(metadataContainer);
        } else {
            context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.WARNING, "Unknown file type: " + file.getAbsolutePath()));
            return;
        }

        Pair<String,SourceFileMetaData> filePair;
        try {
            filePair = FileUtilities.getFileContents(file);
        } catch (Exception exception) {
            context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.ERROR, "Unable to read local file: " + file.getName()));
            return;  // TODO: throw exception?
        }
        member.setBody(filePair.getFirst());
        member.setContentEntityId(filePair.getSecond().getId());
        toolingRestClient.createSObject(member);
        context.processMessage(new CompilerMessage(EMPTY, BuildMessage.Kind.PROGRESS, "Uploading " + file.getName()));
    }

}

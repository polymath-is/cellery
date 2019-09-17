/*
 *   Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.cellery.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.cellery.CelleryConstants;
import io.cellery.CelleryUtils;
import io.cellery.models.Cell;
import io.cellery.models.Meta;
import io.cellery.models.Component;
import io.cellery.models.Composite;
import io.cellery.models.GatewaySpec;
import io.cellery.models.Node;
import io.cellery.models.OIDC;
import io.cellery.models.Tree;
import io.cellery.util.KubernetesClient;
import io.cellery.models.Web;
import io.cellery.models.internal.Dependency;
import io.cellery.models.internal.Image;
import io.cellery.models.internal.ImageComponent;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.ballerinalang.bre.Context;
import org.ballerinalang.bre.bvm.BLangVMErrors;
import org.ballerinalang.bre.bvm.BlockingNativeCallableUnit;
import org.ballerinalang.connector.api.BLangConnectorSPIUtil;
import org.ballerinalang.model.types.BArrayType;
import org.ballerinalang.model.types.BMapType;
import org.ballerinalang.model.types.BTypes;
import org.ballerinalang.model.types.TypeKind;
import org.ballerinalang.model.values.BBoolean;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BString;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.model.values.BValueArray;
import org.ballerinalang.natives.annotations.Argument;
import org.ballerinalang.natives.annotations.BallerinaFunction;
import org.ballerinalang.natives.annotations.ReturnType;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.cellery.CelleryConstants.ANNOTATION_CELL_IMAGE_DEPENDENCIES;
import static io.cellery.CelleryConstants.CELL;
import static io.cellery.CelleryConstants.CELLERY_IMAGE_DIR_ENV_VAR;
import static io.cellery.CelleryConstants.CENTRAL_REGISTRY_HOST;
import static io.cellery.CelleryConstants.COMPONENTS;
import static io.cellery.CelleryConstants.ENV_VARS;
import static io.cellery.CelleryConstants.INGRESSES;
import static io.cellery.CelleryConstants.INSTANCE_NAME;
import static io.cellery.CelleryConstants.INSTANCE_NAME_PLACEHOLDER;
import static io.cellery.CelleryConstants.POD_RESOURCES;
import static io.cellery.CelleryConstants.PROBES;
import static io.cellery.CelleryConstants.YAML;
import static io.cellery.CelleryUtils.appendToFile;
import static io.cellery.CelleryUtils.fileExists;
import static io.cellery.CelleryUtils.getFilesByExtension;
import static io.cellery.CelleryUtils.isCellInstanceRunning;
import static io.cellery.CelleryUtils.printDebug;
import static io.cellery.CelleryUtils.printWarning;
import static io.cellery.CelleryUtils.processEnvVars;
import static io.cellery.CelleryUtils.processProbes;
import static io.cellery.CelleryUtils.processResources;
import static io.cellery.CelleryUtils.processWebIngress;
import static io.cellery.CelleryUtils.randomString;
import static io.cellery.CelleryUtils.replaceInFile;
import static io.cellery.CelleryUtils.toYaml;
import static io.cellery.CelleryUtils.unzip;
import static io.cellery.CelleryUtils.writeToFile;

/**
 * Native function cellery:createInstance.
 */
@BallerinaFunction(
        orgName = "celleryio", packageName = "cellery:0.0.0",
        functionName = "createInstance",
        args = {@Argument(name = "cellImage", type = TypeKind.RECORD),
                @Argument(name = "iName", type = TypeKind.RECORD),
                @Argument(name = "instances", type = TypeKind.MAP),
                @Argument(name = "startDependencies", type = TypeKind.BOOLEAN),
                @Argument(name = "shareDependencies", type = TypeKind.BOOLEAN)
        },
        returnType = {@ReturnType(type = TypeKind.ERROR),
                @ReturnType(type = TypeKind.ARRAY)},
        isPublic = true
)
public class CreateInstance extends BlockingNativeCallableUnit {
    private static final Logger log = LoggerFactory.getLogger(CreateInstance.class);
    private Image image = new Image();
    private String instanceName;
    private Map dependencyInfo = new LinkedHashMap();
    private static Tree dependencyTree = new Tree();
    private BValueArray bValueArray;
    private BMap<String, BValue> bmap;
    private AtomicLong runCount;
    private Map runningInstances;
    private Map dependencyTreeTable;
    private boolean shareDependencies;
    private boolean isRoot;

    public void execute(Context ctx) {
        runningInstances = new HashMap<String, Node>();
        dependencyTreeTable = new HashMap<String, Node>();
        BArrayType bArrayType =
                new BArrayType(ctx.getProgramFile().getPackageInfo(CelleryConstants.CELLERY_PACKAGE).getTypeDefInfo(
                        CelleryConstants.IMAGE_NAME_DEFINITION).typeInfo.getType());
        bValueArray = new BValueArray(bArrayType);
        runCount = new AtomicLong(0L);
        String instanceArg;
        boolean startDependencies = ctx.getBooleanArgument(0);
        shareDependencies = ctx.getBooleanArgument(1);
        final BMap refArgument = (BMap) ctx.getNullableRefArgument(0);
        LinkedHashMap nameStruct = ((BMap) ctx.getNullableRefArgument(1)).getMap();
        String cellName = ((BString) nameStruct.get("name")).stringValue();
        isRoot = ((BBoolean) nameStruct.get("isRoot")).booleanValue();
        instanceName = ((BString) nameStruct.get(INSTANCE_NAME)).stringValue();
        if (instanceName.isEmpty()) {
            instanceName = generateRandomInstanceName(((BString) nameStruct.get("name")).stringValue(),
                    ((BString) nameStruct.get("ver")).stringValue());
        }
        validateMainInstance(instanceName);
        String destinationPath = System.getenv(CELLERY_IMAGE_DIR_ENV_VAR) + File.separator +
                "artifacts" + File.separator + "cellery";
        Map userDependencyLinks = ((BMap) ctx.getNullableRefArgument(2)).getMap();

        String cellYAMLPath = destinationPath + File.separator + cellName + YAML;
        Composite composite;
        if (CELL.equals(((BString) (refArgument.getMap().get("kind"))).stringValue())) {
            instanceArg = "cells.mesh.cellery.io/" + instanceName;
            composite = CelleryUtils.readCellYaml(cellYAMLPath);
        } else {
            instanceArg = "composites.mesh.cellery.io/" + instanceName;
            composite = CelleryUtils.readCompositeYaml(cellYAMLPath);
        }
        if (isRoot) {
            // Must generate dependency tree regardless of whether starting dependencies or not
            // if the instance is root
            try {
                generateDependencyTree(destinationPath + File.separator + "metadata.json");
                if (!startDependencies) {
                    if (((Meta) dependencyTree.getRoot().getData()).getCellDependencies().size() > 0) {
                        validateRootDependencyLinks(userDependencyLinks);
                    }
                }
            } catch (IOException e) {
                String error = "Unable to generate dependency tree";
                log.error(error, e);
            }
            // Validate dependencies provided by user
            validateDependencyLinksAliasNames(userDependencyLinks);
            validateDependencyLinksInstances(userDependencyLinks);
            // Assign user defined instance names to dependent cells
            assignInstanceNames(userDependencyLinks);
            // Assign randomly generated instance names to instances user has not defined names for
            assignRandomInstanceNames();
            Meta rootMeta = (Meta) dependencyTree.getRoot().getData();
            BMap<String, BValue> rootCellInfo = new BMap<>(new BArrayType(BTypes.typeString));
            rootCellInfo.put("org", new BString(rootMeta.getOrg()));
            rootCellInfo.put("name", new BString(rootMeta.getName()));
            rootCellInfo.put("ver", new BString(rootMeta.getVer()));
            rootCellInfo.put("instanceName", new BString(rootMeta.getInstanceName()));
            bmap = BLangConnectorSPIUtil.createBStruct(ctx,
                    CelleryConstants.CELLERY_PACKAGE,
                    CelleryConstants.INSTANCE_STATE_DEFINITION,
                    rootCellInfo, isCellInstanceRunning(rootMeta.getInstanceName()));
            bValueArray.add(runCount.getAndIncrement(), bmap);
            dependencyInfo = generateDependencyInfo();
            if (startDependencies) {
                try {
                    // Display the dependency tree info table
                    displayDependentCellTable();
                    dependencyInfo.forEach((alias, info) -> {
                        String depInstanceName = ((BString) ((BMap) info).getMap().get(INSTANCE_NAME)).stringValue();
                        bmap = BLangConnectorSPIUtil.createBStruct(ctx,
                                CelleryConstants.CELLERY_PACKAGE,
                                CelleryConstants.INSTANCE_STATE_DEFINITION,
                                info, isCellInstanceRunning(depInstanceName), alias);
                        bValueArray.add(runCount.getAndIncrement(), bmap);
                    });
                    // Start the dependency tree
                    startDependencyTree(dependencyTree.getRoot());
                } catch (Exception e) {
                    String error = "Unable to start dependencies";
                    log.error(error, e);
                }
            } else {
                dependencyInfo.forEach((alias, info) -> {
                    bmap = BLangConnectorSPIUtil.createBStruct(ctx,
                            CelleryConstants.CELLERY_PACKAGE,
                            CelleryConstants.INSTANCE_STATE_DEFINITION,
                            info, true, alias);
                    bValueArray.add(runCount.getAndIncrement(), bmap);
                });
            }
        } else {
            dependencyInfo = ((BMap) ctx.getNullableRefArgument(2)).getMap();
        }
        updateDependencyAnnotations(composite, dependencyInfo);
        try {
            processComponents((BMap) refArgument.getMap().get(COMPONENTS));
            composite.getSpec().getComponents().forEach(component -> {
                String componentName = component.getMetadata().getName();
                ImageComponent updatedComponent = this.image.getComponentNameToComponentMap().get(componentName);
                //Replace env values defined in the YAML.
                updateEnvVar(instanceName, component, updatedComponent, dependencyInfo);
                // Update Gateway Config
                if (composite instanceof Cell) {
                    updateGatewayConfig(instanceName, destinationPath, (Cell) composite, updatedComponent);
                }
                // Update liveness and readiness probe
                updateProbes(component, updatedComponent);
                // Update resource limit and requests
                updateResources(component, updatedComponent);
            });
            writeToFile(toYaml(composite), cellYAMLPath);
            // Update cell yaml with instance name
            replaceInFile(cellYAMLPath, "  name: \"" + cellName + "\"\n", "  name: \"" + instanceName + "\"\n");
            // Apply yaml file of the instance
            KubernetesClient.apply(cellYAMLPath);
            KubernetesClient.waitFor("Ready", 30 * 60, instanceArg, "default");
            ctx.setReturnValues(bValueArray);
        } catch (IOException | BallerinaException e) {
            String error = "Unable to persist updated composite yaml " + destinationPath;
            log.error(error, e);
            ctx.setReturnValues(BLangVMErrors.createError(ctx, error + ". " + e.getMessage()));
        } catch (Exception e) {
            String error = "Unable to apply composite yaml " + destinationPath;
            log.error(error, e);
        }
    }

    /**
     * Update Gateway Config.
     *
     * @param instanceName     instance Name
     * @param destinationPath  destination file path
     * @param cell             Cell object
     * @param updatedComponent Updated component object
     */
    private void updateGatewayConfig(String instanceName, String destinationPath, Cell cell,
                                     ImageComponent updatedComponent) {
        GatewaySpec gatewaySpec = cell.getSpec().getGateway().getSpec();
        Web web = updatedComponent.getWeb();
        if (web != null) {
            // Create TLS secret yaml and set the name
            if (StringUtils.isNoneEmpty(web.getTlsKey())) {
                Map<String, String> tlsMap = new HashMap<>();
                tlsMap.put("tls.key",
                        Base64.encodeBase64String(web.getTlsKey().getBytes(StandardCharsets.UTF_8)));
                tlsMap.put("tls.crt",
                        Base64.encodeBase64String(web.getTlsCert().getBytes(StandardCharsets.UTF_8)));
                String tlsSecretName = instanceName + "--tls-secret";
                createSecret(tlsSecretName, tlsMap, destinationPath + File.separator + tlsSecretName + ".yaml");
                gatewaySpec.getIngress().getExtensions().getClusterIngress().getTls().setSecret(tlsSecretName);
            }
            // Set OIDC values
            if (web.getOidc() != null) {
                OIDC oidc = web.getOidc();
                if (StringUtils.isBlank(oidc.getClientSecret()) && StringUtils.isBlank(oidc.getDcrPassword())) {
                    printWarning("OIDC client secret and DCR password are empty.");
                }
                gatewaySpec.getIngress().getExtensions().setOidc(oidc);
            }
        }
        if (web != null) {
            gatewaySpec.getIngress().getExtensions().getClusterIngress().setHost(web.getVhost());
        }

    }

    /**
     * Update Probe configurations.
     *
     * @param component        service Template object
     * @param updatedComponent updated component to process env var
     */
    private void updateProbes(Component component, ImageComponent updatedComponent) {
        Probe livenessProbe = updatedComponent.getLivenessProbe();
        // Override values with updated values
        component.getSpec().getTemplate().getContainers().forEach(container -> {
            if (container.getName().equals(updatedComponent.getName())) {
                if (livenessProbe != null) {
                    Probe probe = container.getLivenessProbe();
                    probe.setInitialDelaySeconds(livenessProbe.getInitialDelaySeconds());
                    probe.setFailureThreshold(livenessProbe.getFailureThreshold());
                    probe.setPeriodSeconds(livenessProbe.getPeriodSeconds());
                    probe.setSuccessThreshold(livenessProbe.getSuccessThreshold());
                    probe.setTimeoutSeconds(livenessProbe.getTimeoutSeconds());
                    container.setLivenessProbe(probe);
                }

                Probe readinessProbe = updatedComponent.getReadinessProbe();
                // Override values with updated values
                if (readinessProbe != null) {
                    Probe probe = container.getReadinessProbe();
                    probe.setInitialDelaySeconds(readinessProbe.getInitialDelaySeconds());
                    probe.setFailureThreshold(readinessProbe.getFailureThreshold());
                    probe.setPeriodSeconds(readinessProbe.getPeriodSeconds());
                    probe.setSuccessThreshold(readinessProbe.getSuccessThreshold());
                    probe.setTimeoutSeconds(readinessProbe.getTimeoutSeconds());
                    container.setReadinessProbe(probe);
                }
            }
        });

    }

    /**
     * Update Resource configurations.
     *
     * @param component        component object from YAML
     * @param updatedComponent updated component to process env var
     */
    private void updateResources(Component component, ImageComponent updatedComponent) {
        ResourceRequirements resourceRequirement = updatedComponent.getResources();
        component.getSpec().getTemplate().getContainers().forEach(container -> {
            if (container.getName().equals(updatedComponent.getName())) {
                container.setResources(resourceRequirement);
            }
        });

    }

    /**
     * Update Environment variables.
     *
     * @param instanceName     Instance Name
     * @param component        component object from YAML
     * @param updatedComponent updated component to process env var
     */
    private void updateEnvVar(String instanceName, Component component, ImageComponent updatedComponent,
                              Map<?, ?> dependencyInfo) {
        printDebug("[" + instanceName + "] cell instance [" + updatedComponent.getName() + "] component environment " +
                "variables:");
        Map<String, String> updatedParams = updatedComponent.getEnvVars();
        // Override values with updated values
        component.getSpec().getTemplate().getContainers().forEach(container -> {
            container.getEnv().forEach(envVar -> {
                if (updatedParams.containsKey(envVar.getName()) && !updatedParams.get(envVar.getName()).isEmpty()) {
                    envVar.setValue(updatedParams.get(envVar.getName()));
                }
                String value = envVar.getValue();
                if (value.isEmpty()) {
                    printWarning("Value is empty for environment variable \"" + envVar.getName() + "\"");
                    return;
                }
                // Validate and replace dependency instance names
                envVar.setValue(value.replace(INSTANCE_NAME_PLACEHOLDER, instanceName));
                dependencyInfo.forEach((alias, info) -> {
                    String aliasPlaceHolder = "{{" + alias + "}}";
                    String depInstanceName = ((BString) ((BMap) info).getMap().get(INSTANCE_NAME)).stringValue();
                    if (value.contains(aliasPlaceHolder)) {
                        envVar.setValue(value.replace(aliasPlaceHolder, depInstanceName));
                    }
                });
                printDebug("\t" + envVar.getName() + "=" + envVar.getValue());
            });
        });
    }

    /**
     * Update the dependencies annotation with dependent instance names.
     *
     * @param composite      Cell definition
     * @param dependencyInfo dependency alias information map
     */
    private void updateDependencyAnnotations(Composite composite, Map dependencyInfo) {
        Gson gson = new Gson();
        Dependency[] dependencies = gson.fromJson(composite.getMetadata().getAnnotations()
                .get(ANNOTATION_CELL_IMAGE_DEPENDENCIES), Dependency[].class);
        Arrays.stream(dependencies).forEach(dependency -> {
            dependency.setInstance(((BString) ((BMap) dependencyInfo.get(dependency.getAlias())).getMap().get(
                    INSTANCE_NAME)).stringValue());
            dependency.setAlias(null);
        });
        composite.getMetadata().getAnnotations().put(ANNOTATION_CELL_IMAGE_DEPENDENCIES, gson.toJson(dependencies));
    }

    /**
     * Add update components to Image.
     *
     * @param components component map
     */
    private void processComponents(BMap<?, ?> components) {
        components.getMap().forEach((key, componentValue) -> {
            ImageComponent component = new ImageComponent();
            LinkedHashMap attributeMap = ((BMap) componentValue).getMap();
            // Set mandatory fields.
            component.setName(((BString) attributeMap.get("name")).stringValue());

            //Process modifiable fields
            if (attributeMap.containsKey(PROBES)) {
                processProbes(((BMap<?, ?>) attributeMap.get(PROBES)).getMap(), component);
            }
            if (attributeMap.containsKey(INGRESSES)) {
                processIngress(((BMap<?, ?>) attributeMap.get(INGRESSES)).getMap(), component);
            }
            if (attributeMap.containsKey(ENV_VARS)) {
                processEnvVars(((BMap<?, ?>) attributeMap.get(ENV_VARS)).getMap(), component);
            }
            if (attributeMap.containsKey(POD_RESOURCES)) {
                processResources(((BMap<?, ?>) attributeMap.get(POD_RESOURCES)).getMap(), component);
            }
            image.addComponent(component);
        });
    }

    /**
     * Process and update Ingress of a component.
     *
     * @param ingressMap ingress attribute map
     * @param component  component to be updatedÒ
     */
    private void processIngress(LinkedHashMap<?, ?> ingressMap, ImageComponent component) {
        ingressMap.forEach((key, ingressValues) -> {
            BMap ingressValueMap = ((BMap) ingressValues);
            LinkedHashMap attributeMap = ingressValueMap.getMap();
            if ("WebIngress".equals(ingressValueMap.getType().getName())) {
                processWebIngress(component, attributeMap);
            }
        });
    }

    /**
     * Create a secret file.
     *
     * @param instanceName    Cell Instance Name
     * @param data            secret data
     * @param destinationPath path to save the secret yaml
     */
    private void createSecret(String instanceName, Map<String, String> data, String destinationPath) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(instanceName)
                .endMetadata()
                .withData(data)
                .build();
        try {
            writeToFile(toYaml(secret), destinationPath);
        } catch (IOException e) {
            throw new BallerinaException("Error while generating secrets for instance " + instanceName);
        }
    }

    /**
     * Generate cell dependency information of the cell instance.
     *
     * @return map of dependency info
     */
    private Map generateDependencyInfo() {
        BMap<String, BValue> dependencyInfoMap = new BMap<>(new BMapType(new BArrayType(BTypes.typeString)));
        for (Map.Entry<String, Meta> dependentCell :
                ((Meta) dependencyTree.getRoot().getData()).getCellDependencies().entrySet()) {
            BMap<String, BValue> dependentCellMap = new BMap<>(new BArrayType(BTypes.typeString));
            dependentCellMap.put("org", new BString(dependentCell.getValue().getOrg()));
            dependentCellMap.put("name", new BString(dependentCell.getValue().getName()));
            dependentCellMap.put("ver", new BString(dependentCell.getValue().getVer()));
            dependentCellMap.put("instanceName", new BString(dependentCell.getValue().getInstanceName()));
            dependencyInfoMap.put(dependentCell.getKey(), dependentCellMap);
        }
        return ((BMap) dependencyInfoMap).getMap();
    }

    /**
     * Generate dependency tree.
     *
     * @param metadataJsonPath path to the metadata.json of the cell
     * @throws IOException if dependency tree generation fails
     */
    private void generateDependencyTree(String metadataJsonPath) throws IOException {
        //read json file data to String
        byte[] jsonData = Files.readAllBytes(Paths.get(metadataJsonPath));
        //create ObjectMapper instance
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        //convert json string to object
        Meta rootMeta = objectMapper.readValue(jsonData, Meta.class);
        Node<Meta> rootNode = new Node<>(rootMeta);
        rootMeta.setInstanceName(instanceName);
        // Set as root node
        dependencyTree.setRoot(rootNode);
        // Generating dependency tree
        buildDependencyTree(rootNode);
    }

    /**
     * Build dependency tree.
     *
     * @param node node that will be added to the tree
     */
    private void buildDependencyTree(Node<Meta> node) {
        Meta cell = node.getData();
        Map<String, Meta> dependentCells = cell.getCellDependencies();
        if (dependentCells.size() > 0) {
            for (Map.Entry<String, Meta> dependentCell : dependentCells.entrySet()) {
                Node<Meta> childNode = node.addChild(dependentCell.getValue());
                buildDependencyTree(childNode);
            }
        }
        dependencyTree.addNode(node);
    }

    /**
     * Display cell dependency information table.
     */
    private void displayDependentCellTable() {
        ArrayList<Node> tree = new ArrayList<>();
        if (shareDependencies) {
            // Multiple instances with same cell image would have only one entry in the table
            for (Node node : dependencyTree.getTree()) {
                Meta meta = (Meta) node.getData();
                String cellImageName = meta.getOrg() + "/" + meta.getName() + ":" + meta.getVer();
                if (!dependencyTreeTable.containsKey(cellImageName)) {
                    dependencyTreeTable.put(cellImageName, node);
                    tree.add(node);
                }
            }
        } else {
            tree = dependencyTree.getTree();
        }
        PrintStream out = System.out;
        out.println("-----------------------------------------------------------------------------");
        out.printf("%-20s %-30s %-20s", "INSTANCE NAME", "CELL IMAGE", "USED INSTANCE");
        out.println();
        out.println("-----------------------------------------------------------------------------");
        for (Node node : tree) {
            Meta meta = (Meta) node.getData();
            String image = meta.getOrg() + File.separator + meta.getName() + ":" + meta.getVer();
            String availability = "To be Created";
            if (isCellInstanceRunning(meta.getInstanceName())) {
                availability = "Available in Runtime";
            }
            out.format("%-20s %-30s %-20s", meta.getInstanceName(), image, availability);
            out.println();
        }
        out.println("-----------------------------------------------------------------------------");
    }

    /**
     * Start a cell instance.
     *
     * @param org              organization
     * @param name             cell name
     * @param version          cell version
     * @param cellInstanceName cell instance name
     * @throws Exception if cell start fails
     */
    private void startInstance(String org, String name, String version, String cellInstanceName, String dependentCells,
                               boolean shareDependencies)
            throws Exception {
        Path imageDir = Paths.get(System.getProperty("user.home"), ".cellery", "repo", org, name, version,
                name + ".zip");
        if (!fileExists(imageDir.toString())) {
            pullImage(CENTRAL_REGISTRY_HOST, org, name, version);
        }
        Path tempDir = Paths.get(System.getProperty("user.home"), ".cellery", "tmp");
        Path tempBalFileDir = Files.createTempDirectory(Paths.get(tempDir.toString()), "cellery-cell-image");
        unzip(imageDir.toString(), tempBalFileDir.toString());
        String tempBalFile = getFilesByExtension(tempBalFileDir + File.separator + "src", "bal").
                get(0).toString();
        String ballerinaMain = "public function main(string action, cellery:ImageName iName, map<cellery:ImageName> " +
                "instances, boolean startDependencies, boolean shareDependencies) returns error? {\n" +
                "\tcellery:InstanceState[]|error? result = run(iName, instances, startDependencies, " +
                "shareDependencies);\n" +
                "\tif (result is error?) {\n" +
                "\t\treturn result;\n" +
                "\t}\n" +
                "}";
        appendToFile(ballerinaMain, tempBalFile);
        // Create a cell image json object
        JSONObject image = new JSONObject();
        image.put("org", org);
        image.put("name", name);
        image.put("ver", version);
        image.put("instanceName", cellInstanceName);
        Map<String, String> environment = new HashMap<>();
        environment.put(CELLERY_IMAGE_DIR_ENV_VAR, tempBalFileDir.toString());
        String shareDependenciesFlag = "false";
        if (shareDependencies) {
            shareDependenciesFlag = "true";
        }
        CelleryUtils.executeShellCommand(null, CelleryUtils::printInfo, CelleryUtils::printInfo,
                environment, "ballerina", "run", tempBalFile, "run", image.toString(),
                dependentCells, "false", shareDependenciesFlag);
    }

    /**
     * Start the dependency tree.
     *
     * @param node node which will be used to initiate the starting of the dependency tree
     * @throws Exception if dependency tree start fails
     */
    private void startDependencyTree(Node<Meta> node) throws Exception {
        Meta meta = node.getData();
        for (Node<Meta> childNode : node.getChildren()) {
            if (isCellInstanceRunning(childNode.getData().getInstanceName())) {
                continue;
            }
            startDependencyTree(childNode);
        }
        // Once all dependent cells are started or there are no dependent cells, start the current instance
        String cellMetaInstanceName = meta.getInstanceName();
        String cellImageName = meta.getOrg() + "/" + meta.getName() + ":" + meta.getVer();
        // Start the cell instance if not already running
        // This will not start root instance
        if (!dependencyTree.getRoot().equals(node)) {
            if (shareDependencies && runningInstances.containsKey(cellImageName)) {
                // If a shared instance is running with the same cell image name (org/name:version) change the
                // instance name of the current cell to that cell's instance name
                node.getData().setInstanceName(((Meta) ((Node) runningInstances.
                        get(cellImageName)).getData()).getInstanceName());
            } else {
                JSONObject dependentCellsMap = new JSONObject();
                for (Map.Entry<String, Meta> dependentCell : meta.getCellDependencies().entrySet()) {
                    // Create a dependent cell image json object
                    JSONObject dependentCellImage = new JSONObject();
                    dependentCellImage.put("org", dependentCell.getValue().getOrg());
                    dependentCellImage.put("name", dependentCell.getValue().getName());
                    dependentCellImage.put("ver", dependentCell.getValue().getVer());
                    dependentCellImage.put("instanceName", dependentCell.getValue().getInstanceName());
                    dependentCellsMap.put(dependentCell.getKey(), dependentCellImage);
                }
                startInstance(meta.getOrg(), meta.getName(), meta.getVer(), cellMetaInstanceName,
                        dependentCellsMap.toString(), shareDependencies);
                runningInstances.put(cellImageName, node);
            }
        }
    }

    /**
     * Generate a random instance name.
     *
     * @param name cell name
     * @param ver  cell version
     * @return random instance name generated
     */
    private String generateRandomInstanceName(String name, String ver) {
        String instanceName = name + ver + randomString(4);
        return instanceName.replace(".", "");
    }

    /**
     * Assign names to dependent cells of a cell instance.
     *
     * @param dependencyLinks links to the dependent cells
     */
    private void assignInstanceNames(Map<?, ?> dependencyLinks) {
        // Iterate the dependency tree
        for (Node<Meta> node : dependencyTree.getTree()) {
            Meta meta = node.getData();
            for (Map.Entry<String, Meta> dependentCell : meta.getCellDependencies().entrySet()) {
                if (dependencyLinks.containsKey(dependentCell.getKey())) {
                    // Check if there is a dependency Alias equal to the key of dependency cell
                    // If there exists an alias assign its instance name as the dependent cell instance name
                    dependentCell.getValue().setInstanceName(((BString) (((BMap) (dependencyLinks.get(
                            dependentCell.getKey()))).getMap().get(INSTANCE_NAME))).stringValue());
                }
            }
        }
    }

    /**
     * Assign random instance names for dependent cells which do not have an instance name.
     */
    private void assignRandomInstanceNames() {
        for (Node node : dependencyTree.getTree()) {
            Meta meta = (Meta) node.getData();
            if (!dependencyTree.getRoot().equals(node)) {
                if ((meta.getInstanceName() == null) || meta.getInstanceName().isEmpty()) {
                    meta.setInstanceName(generateRandomInstanceName(meta.getName(), meta.getVer()));
                }
            }
        }
    }

    /**
     * Validate whether dependent cells with links given are running.
     *
     * @param dependencyLinks links to the dependent cells
     */
    private void validateDependencyLinksInstances(Map<?, ?> dependencyLinks) {
        ArrayList<String> invalidInstances = new ArrayList<>();
        if (dependencyLinks.size() > 0) {
            dependencyLinks.forEach((alias, info) -> {
                String depInstanceName = ((BString) ((BMap) info).getMap().get(INSTANCE_NAME)).stringValue();
                if (!isCellInstanceRunning(depInstanceName)) {
                    invalidInstances.add(depInstanceName);
                }
            });
        }
        if (invalidInstances.size() > 0) {
            String errMsg = "Cell dependency validation failed. Instances " + String.join(", ", invalidInstances)
                    + " not running";
            throw new BallerinaException(errMsg);
        }
    }

    /**
     * Validate whether dependency aliases of dependent cells are correct.
     *
     * @param dependencyLinks links to the dependent cells
     */
    private void validateDependencyLinksAliasNames(Map<?, ?> dependencyLinks) {
        ArrayList<String> invalidAliases = new ArrayList<>();
        if (dependencyLinks.size() > 0) {
            dependencyLinks.forEach((alias, info) -> {
                boolean invalidAlias = true;
                // Iterate the dependency tree to check if the user given alias name exists
                for (Node node : dependencyTree.getTree()) {
                    Meta meta = (Meta) node.getData();
                    if (meta.getCellDependencies().size() > 0 && meta.getCellDependencies().containsKey
                            (alias.toString())) {
                        invalidAlias = false;
                    }
                }
                if (invalidAlias) {
                    invalidAliases.add(alias.toString());
                }
            });
        }
        if (invalidAliases.size() > 0) {
            String errMsg = "Cell dependency validation failed. Aliases " + String.join(", ", invalidAliases)
                    + " invalid";
            throw new BallerinaException(errMsg);
        }
    }

    /**
     * Validate whether all immediate dependencies of root have been defined when running without starting dependencies.
     *
     * @param dependencyLinks links to the dependent cells
     */
    private void validateRootDependencyLinks(Map<?, ?> dependencyLinks) {
        ArrayList<String> missingAliases = new ArrayList<>();
        // Iterate the dependency tree
        for (Node node : dependencyTree.getTree()) {
            Meta meta = (Meta) node.getData();
            Map<String, Meta> dependentCells = meta.getCellDependencies();
            if (dependentCells.size() > 0) {
                for (Map.Entry<String, Meta> dependentCell : dependentCells.entrySet()) {
                    if (!dependencyLinks.containsKey(dependentCell.getKey())) {
                        missingAliases.add(dependentCell.getKey());
                    }
                }
            }
        }
        if (missingAliases.size() > 0) {
            String errMsg = "Cell dependency validation failed. All links to dependent cells should be defined when " +
                    "running instance without starting dependencies. Missing dependency aliases: " +
                    String.join(", ", missingAliases);
            throw new BallerinaException(errMsg);
        }
    }

    /**
     * Validate if there is another cell instance running with the same instance name.
     *
     * @param instanceName cell instance name
     */
    private void validateMainInstance(String instanceName) {
        if (isCellInstanceRunning(instanceName)) {
            String errMsg = "instance to be created should not be present in the runtime, instance " + instanceName +
                    " is already available in the runtime";
            throw new BallerinaException(errMsg);
        }
    }

    /**
     * Pull cell image.
     *
     * @param registry name of the registry from which the cell is being pulled from
     * @param org cell organization
     * @param name cell name
     * @param version cell version
     */
    private void pullImage(String registry, String org, String name, String version) {
        Map<String, String> environment = new HashMap<>();
        CelleryUtils.executeShellCommand(null, CelleryUtils::printInfo, CelleryUtils::printInfo,
                environment, "cellery", "pull", registry + File.separator + org + File.separator +
                        name + ":" + version);
    }
}

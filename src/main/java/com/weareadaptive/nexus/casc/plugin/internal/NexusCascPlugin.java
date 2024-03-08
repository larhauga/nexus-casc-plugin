package com.weareadaptive.nexus.casc.plugin.internal;

import com.weareadaptive.nexus.casc.plugin.internal.config.*;
import org.apache.shiro.util.ThreadContext;
import org.eclipse.sisu.Description;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.sonatype.nexus.CoreApi;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.capability.*;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.common.app.BaseUrlManager;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.app.NotWritableException;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.routing.*;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.security.SecurityApi;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.security.user.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;

@Named("cascPlugin")
@Description("Casc Plugin")
// Plugin must run after CAPABILITIES phase as otherwise we can not load/patch existing capabilities
@ManagedLifecycle(phase = ManagedLifecycle.Phase.TASKS)
@Singleton
public class NexusCascPlugin extends StateGuardLifecycleSupport {
    private final BaseUrlManager baseUrlManager;
    private final CoreApi coreApi;
    private final SecurityApi securityApi;
    private final SecuritySystem securitySystem;
    private final CleanupPolicyStorage cleanupPolicyStorage;
    private final Interpolator interpolator;
    private final RepositoryManager repositoryManager;
    private final BlobStoreManager blobStoreManager;
    private final RealmManager realmManager;
    private final CapabilityRegistry capabilityRegistry;
    private final RoutingRuleStore routingRuleStore;
    private final RoutingRuleHelper routingRuleHelper;
    private final RepositoryPermissionChecker repositoryPermissionChecker;

    @Inject
    public NexusCascPlugin(
            final BaseUrlManager baseUrlManager,
            final CoreApi coreApi,
            final SecurityApi securityApi,
            final SecuritySystem securitySystem,
            final CleanupPolicyStorage cleanupPolicyStorage,
            final Interpolator interpolator,
            final RepositoryManager repositoryManager,
            final BlobStoreManager blobStoreManager,
            final RealmManager realmManager,
            final CapabilityRegistry capabilityRegistry,
            final RoutingRuleStore routingRuleStore,
            final RoutingRuleHelper routingRuleHelper,
            final RepositoryPermissionChecker repositoryPermissionChecker
        ) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        this.baseUrlManager = baseUrlManager;
        this.coreApi = coreApi;
        this.securityApi = securityApi;
        this.securitySystem = securitySystem;
        this.blobStoreManager = blobStoreManager;
        this.cleanupPolicyStorage = cleanupPolicyStorage;
        this.interpolator = interpolator;
        this.repositoryManager = repositoryManager;
        this.realmManager = realmManager;
        this.capabilityRegistry = capabilityRegistry;
        this.routingRuleStore = routingRuleStore;
        this.routingRuleHelper = routingRuleHelper;
        this.repositoryPermissionChecker = repositoryPermissionChecker;
    }

    @Override
    protected void doStart() throws Exception {
        String configFile = System.getenv("NEXUS_CASC_CONFIG");
        if (configFile == null) {
            log.error("Env var NEXUS_CASC_CONFIG not found");
            return;
        }

        Config config;
        Yaml yaml = new Yaml(new Constructor(Config.class, new LoaderOptions()));
        try {
            String yml = interpolator.interpolate(new String(Files.readAllBytes(Paths.get(configFile))));
            config = yaml.load(yml);
        } catch (IOException e) {
            log.error("Failed to load config file from {}", configFile, e);
            return;
        }

        ConfigCore core = config.getCore();
        if (core != null) {
            applyBaseUrlConfig(core);
            applyHTTPConfig(core);
        }

        baseUrlManager.detectAndHoldUrl();

        ConfigRepository repository = config.getRepository();
        if (repository != null) {
            applyRepositoryConfig(repository);
        }

        ConfigSecurity security = config.getSecurity();
        if (security != null) {
            applySecurityConfig(security);
        }

        List<ConfigCapability> capabilities = config.getCapabilities();
        Boolean pruneCapabilitiesByType = config.getPruneCapabilitiesByType();
        if (capabilities != null) {
            applyCapabilitiesConfig(capabilities, pruneCapabilitiesByType == null || pruneCapabilitiesByType);
        }
    }

    private void applyBaseUrlConfig(ConfigCore core) {
        if (core.getBaseUrl() != null) {
            String baseUrl = core.getBaseUrl().trim();
            log.info("Setting baseUrl to {}", baseUrl);
            coreApi.baseUrl(baseUrl);
        }
    }
    private void applyHTTPConfig(ConfigCore core) {
        if (core.getUserAgentCustomization() != null){
            coreApi.userAgentCustomization(core.getUserAgentCustomization());
            log.info("UserAgent string set with "+core.getUserAgentCustomization());
        }

        if (core.getConnectionTimeout() > 0) {
            coreApi.connectionTimeout(core.getConnectionTimeout());
            log.info("HTTP connection timeout set to "+core.getConnectionTimeout()+" seconds");
        }

        if (core.getConnectionRetryAttempts() > 0) {
            coreApi.connectionRetryAttempts(core.getConnectionRetryAttempts());
            log.info("HTTP connection retry attempts set to "+core.getConnectionRetryAttempts()+" times");
        }

        applyHttpProxyConfig(core.getHttpProxy(), false);
        applyHttpProxyConfig(core.getHttpsProxy(), true);

        if (core.getNonProxyHosts() != null && core.getNonProxyHosts().size() > 0) {
            String[] noProxyHosts = core.getNonProxyHosts().stream()
                    .map(String::trim)
                    .filter(host -> !host.isEmpty())
                    .toArray(String[]::new);

            log.info("Setting nonProxyHosts to {}", String.join(",", noProxyHosts));
            coreApi.nonProxyHosts(noProxyHosts);
        }
    }

    private void applyHttpProxyConfig(ConfigHttpProxy httpProxy, Boolean isHTTPs) {
        String removeMessage = "No proxy defined, removing any existing "+(isHTTPs ? "HTTPs": "HTTP")+" proxy";
        if (httpProxy != null){
            if (httpProxy.getHost() != null){
                int portNumber = httpProxy.getPort() > 0 ? httpProxy.getPort() : 80;
                String protocol = isHTTPs ? "HTTPs" : "HTTP";
                String message = protocol + " proxy "+ httpProxy.getHost()+":"+ portNumber+ " is set";
                if (httpProxy.getUsername() != null && httpProxy.getNtlmHost() != null){
                    if (isHTTPs)
                        coreApi.httpsProxyWithNTLMAuth(httpProxy.getHost(), portNumber, httpProxy.getUsername(), httpProxy.getPassword(), httpProxy.getNtlmHost(), httpProxy.getNtlmDomain());
                    else
                        coreApi.httpProxyWithNTLMAuth(httpProxy.getHost(), portNumber, httpProxy.getUsername(), httpProxy.getPassword(), httpProxy.getNtlmHost(), httpProxy.getNtlmDomain());
                    message += " with NTLM authentication";
                } else if (httpProxy.getUsername() != null) {
                    if (isHTTPs)
                        coreApi.httpsProxyWithBasicAuth(httpProxy.getHost(), portNumber, httpProxy.getUsername(), httpProxy.getPassword());
                    else
                        coreApi.httpProxyWithBasicAuth(httpProxy.getHost(), portNumber, httpProxy.getUsername(), httpProxy.getPassword());
                    message += " with basic authentication";
                } else {
                    if (isHTTPs)
                        coreApi.httpsProxy(httpProxy.getHost(), portNumber);
                    else
                        coreApi.httpProxy(httpProxy.getHost(), portNumber);
                }
                log.info(message);
            } else {
                log.error("Missing proxy host name");
            }
        } else if (isHTTPs) {
            coreApi.removeHTTPSProxy();
            log.info(removeMessage);
        } else {
            coreApi.removeHTTPProxy();
            log.info(removeMessage);
        }
    }

    private void applyCapabilitiesConfig(List<ConfigCapability> capabilities, boolean pruneByType) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {

        Map<CapabilityType, List<ConfigCapability>> byType = capabilities.stream()
                .collect(groupingBy(
                        cc -> CapabilityType.capabilityType(cc.getType()),
                        toList()
                ));

        Map<CapabilityType, List<CapabilityContext>> existingByType = capabilityRegistry.getAll().stream()
                .map(CapabilityReference::context)
                .collect(groupingBy(CapabilityContext::type, toCollection(ArrayList::new)));

        for (Map.Entry<CapabilityType, List<ConfigCapability>> ent : byType.entrySet()) {
            CapabilityType type = ent.getKey();
            log.info("type={}", type);
            List<CapabilityContext> existingWithType = existingByType.get(type);

            for (ConfigCapability capabilityConfig : ent.getValue()) {

                int existingIndex = -1;
                if (existingWithType != null) {
                    int bestMatch = Integer.MAX_VALUE; // Smaller is better
                    for (int i = 0; i < existingWithType.size(); ++i) {
                        Map<String, String> existingProperties = existingWithType.get(i).properties();
                        Map<String, String> configProperties = capabilityConfig.getAttributes();

                        int mismatchedPropertyCount;
                        if(configProperties == null) {
                            mismatchedPropertyCount = existingProperties == null ? 0 : existingProperties.size();
                        } else if(existingProperties == null) {
                            mismatchedPropertyCount = configProperties.size();
                        } else {
                            Set<String> totalKeys = new HashSet<>(existingProperties.size());
                            totalKeys.addAll(existingProperties.keySet());
                            totalKeys.addAll(configProperties.keySet());

                            mismatchedPropertyCount = totalKeys.size();
                            for (String key : totalKeys) {
                                if (Objects.equals(configProperties.get(key), existingProperties.get(key))) {
                                    --mismatchedPropertyCount;
                                }
                            }
                        }

                        if (mismatchedPropertyCount < bestMatch) {
                            existingIndex = i;
                            bestMatch = mismatchedPropertyCount;
                        }
                    }
                }

                if (existingIndex >= 0) {
                    CapabilityContext existing = existingWithType.remove(existingIndex);
                    boolean enabled = capabilityConfig.getEnabled() == null ? existing.isEnabled() : capabilityConfig.getEnabled();
                    CapabilityIdentity id = existing.id();

                    log.info("Updating capability of type {} and id {}", capabilityConfig.getType(), id);

                    capabilityRegistry.update(
                            id,
                            enabled,
                            capabilityConfig.getNotes(),
                            capabilityConfig.getAttributes()
                    );
                } else {
                    log.info("Creating capability of type {}", capabilityConfig.getType());

                    boolean enabled = capabilityConfig.getEnabled() == null || capabilityConfig.getEnabled();
                    capabilityRegistry.add(
                            type,
                            enabled,
                            capabilityConfig.getNotes(),
                            capabilityConfig.getAttributes()
                    );
                }

            }

            if (pruneByType && existingWithType != null) {
                for (CapabilityContext remaining : existingWithType) {
                    log.info("Removing capability of type {} and id {}", remaining.type(), remaining.id());
                    capabilityRegistry.remove(remaining.id());
                }
            }
        }
    }

    private void applyRepositoryConfig(ConfigRepository repository) {
        if (repository.getBlobStores() != null) {
            repository.getBlobStores().forEach(configBlobStore -> {
                if (configBlobStore.getType().equals(FileBlobStore.TYPE) &&
                        (configBlobStore.getAttributes().get("file") == null
                        || configBlobStore.getAttributes().get("file").get("path") == null
                        || !(configBlobStore.getAttributes().get("file").get("path") instanceof String))) {
                    log.error(".attributes.file.path of blob store {} must be a string!", configBlobStore.getName());
                    return;
                }

                BlobStore existingBlobStore = blobStoreManager.get(configBlobStore.getName());

                if (existingBlobStore != null) {
                    BlobStoreConfiguration existingBlobStoreConfig = existingBlobStore.getBlobStoreConfiguration();

                    if (configBlobStore.getType().equals(FileBlobStore.TYPE)) {
                        if (!configBlobStore.getAttributes().get("file").get("path").equals(
                             existingBlobStoreConfig.getAttributes().get("file").get("path"))) {
                            log.error("Can not update .attributes.file.path for blob stores. Blob store {}, current path: {}, new path {}",
                                    configBlobStore.getName(), existingBlobStoreConfig.getAttributes().get("file").get("path"),
                                    configBlobStore.getAttributes().get("file").get("path"));
                            return;
                        }
                    }

                    if (!configBlobStore.getType().equals(existingBlobStoreConfig.getType())) {
                        log.error("Can not update type of blob stores. Blob store {}, current type: {}, new type {}",
                                configBlobStore.getName(), existingBlobStoreConfig.getType(),
                                configBlobStore.getType());
                        return;
                    }

                    existingBlobStoreConfig.setAttributes(configBlobStore.getAttributes());

                    try {
                        blobStoreManager.update(existingBlobStoreConfig);
                    } catch (Exception e) {
                        log.error("Could not update blob store {}", configBlobStore.getName(), e);
                    }
                } else {
                    BlobStoreConfiguration config = blobStoreManager.newConfiguration();
                    config.setName(configBlobStore.getName());
                    config.setAttributes(configBlobStore.getAttributes());
                    config.setType(configBlobStore.getType());
                    try {
                        blobStoreManager.create(config);
                    } catch (Exception e) {
                        log.error("Could not create blob store {}", configBlobStore.getName(), e);
                    }
                }
            });
        } else if (repository.getPruneBlobStores() != null && repository.getPruneBlobStores()) {
            log.warn("repository.pruneBlobStores has no effect when no blob stores are configured!");
        }

        if (repository.getCleanupPolicies() != null) {
            repository.getCleanupPolicies().forEach(cp -> {
                CleanupPolicy existingCp = cleanupPolicyStorage.get(cp.getName());

                if (existingCp != null) {
                    existingCp.setCriteria(cp.getCriteria());
                    existingCp.setFormat(cp.getFormat());
                    existingCp.setNotes(cp.getNotes());
                    existingCp.setMode(cp.getMode());
                    cleanupPolicyStorage.update(existingCp);
                } else {
                    CleanupPolicy newCp = cleanupPolicyStorage.newCleanupPolicy();
                    newCp.setName(cp.getName());
                    newCp.setNotes(cp.getNotes());
                    newCp.setFormat(cp.getFormat());
                    newCp.setMode(cp.getMode());
                    newCp.setCriteria(cp.getCriteria());
                    cleanupPolicyStorage.add(newCp);
                }
            });

            if (repository.getPruneCleanupPolicies() != null && repository.getPruneCleanupPolicies()) {
                cleanupPolicyStorage.getAll().forEach(existingCp -> {
                    if (repository.getCleanupPolicies().stream().noneMatch(cp -> existingCp.getName().equals(cp.getName()))) {
                        log.info("Pruning cleanup policy {}", existingCp.getName());
                        cleanupPolicyStorage.remove(existingCp);
                    }
                });
            }
        } else if (repository.getPruneCleanupPolicies() != null && repository.getPruneCleanupPolicies()) {
            log.warn("repository.pruneCleanupPolicies has no effect when no cleanup policies are configured!");
        }

        if (repository.getRoutingRules() != null && !repository.getRoutingRules().isEmpty()) {
            repository.getRoutingRules().forEach(this::applyRoutingRuleConfig);
            routingRuleStore.list().forEach(existingRule -> {
                if (repository.getRoutingRules().stream().noneMatch(rr -> existingRule.name().equals(rr.getName()))) {
                    log.info("Pruning routing rule {}", existingRule.name());
                    routingRuleStore.delete(existingRule);
                }
            });
        }

        if (repository.getRepositories() != null) {
            repository.getRepositories().forEach(repoConfig -> {
                Repository existingRepo = repositoryManager.get(repoConfig.getName());

                if (existingRepo != null) {
                    if (!existingRepo.getConfiguration().getRecipeName().equals(repoConfig.getRecipeName())) {
                        log.error("Can not change recipeName of repo {}", repoConfig.getName());
                        return;
                    }

                    Configuration configuration = existingRepo.getConfiguration();
                    log.info("repo config: {}", configuration);

                    configuration.setAttributes(repoConfig.getAttributes());

                    if ( repoConfig.getRoutingRuleName() != null) {
                        String ruleName = repoConfig.getRoutingRuleName();
                        RoutingRule rule = routingRuleStore.getByName(ruleName);
                        if (rule != null) {
                            configuration.setRoutingRuleId(rule.id());
                        } else {
                            log.error("Routing rule {} does not exist", ruleName);
                        }
                    }

                    patchRepoAttributes(repoConfig.getAttributes());

                    if (repoConfig.getOnline() != null) {
                        configuration.setOnline(repoConfig.getOnline());
                    }

                    try {
                        repositoryManager.update(configuration);
                    } catch (Exception e) {
                        log.error("Failed to update repo {}", repoConfig.getName(), e);
                    }
                } else {
                    Configuration configuration = repositoryManager.newConfiguration();
                    configuration.setRepositoryName(repoConfig.getName());
                    configuration.setRecipeName(repoConfig.getRecipeName());
                    configuration.setAttributes(repoConfig.getAttributes());
                    configuration.setOnline(repoConfig.getOnline() != null ? repoConfig.getOnline() : true);

                    patchRepoAttributes(repoConfig.getAttributes());

                    try {
                        repositoryManager.create(configuration);
                    } catch (Exception e) {
                        log.error("Failed to create repo {}", repoConfig.getName(), e);
                    }
                }
            });

            if (repository.getPruneRepositories() != null && repository.getPruneRepositories()) {
                repositoryManager.browse().forEach(existingRepo -> {
                    if (repository.getRepositories().stream().noneMatch(repo -> existingRepo.getName().equals(repo.getName()))) {
                        log.info("Pruning repository {}", existingRepo.getName());
                        log.info(existingRepo.getConfiguration().toString());
                        try {
                            repositoryManager.delete(existingRepo.getName());
                        } catch (Exception e) {
                            log.error("Failed to delete repo {}", existingRepo.getName(), e);
                        }
                    }
                });
            }
        } else if (repository.getPruneRepositories() != null && repository.getPruneRepositories()) {
            log.warn("repository.pruneRepositories has no effect when no repositories are configured!");
        }

        // we prune blob stores here as pruned repos might rely on them
        if (repository.getBlobStores() != null && repository.getPruneBlobStores() != null && repository.getPruneBlobStores()) {
            blobStoreManager.browse().forEach(existingBlobStore -> {
                String name = existingBlobStore.getBlobStoreConfiguration().getName();
                if (repository.getBlobStores().stream().noneMatch(blobStore -> blobStore.getName().equals(name))) {
                    log.info("pruning blob store {}", name);
                    try {
                        blobStoreManager.delete(name);
                    } catch (Exception e) {
                        log.error("Failed to prune blob store {}", name, e);
                    }
                }
            });
        }
    }

    private void patchRepoAttributes(Map<String, Map<String, Object>> attributes) {
        Map<String, Object> cleanup = attributes.get("cleanup");

        if (cleanup != null) {
            Object policyName = cleanup.get("policyName");

            if (policyName != null) {
                if (policyName instanceof String) {
                    log.warn("repository.repositories[].attributes.cleanup.policyName should be a list as of Nexus 3.19.1, converting it for you");
                    HashSet<Object> set = new HashSet<>();
                    set.add(policyName);
                    cleanup.put("policyName", set);
                } else if (policyName instanceof List) {
                    cleanup.put("policyName", new HashSet<>((Collection<Object>) policyName));
                }
            }
        }
    }

    private void applyRoutingRuleConfig(ConfigRoutingRule routingRuleConfig) {
        RoutingRule existingRule = routingRuleStore.getByName(routingRuleConfig.getName());
        if (existingRule != null) {
            existingRule.matchers(routingRuleConfig.getMatchers());
            existingRule.description(routingRuleConfig.getDescription());
            existingRule.mode(RoutingMode.valueOf(routingRuleConfig.getMode()));
            routingRuleStore.update(existingRule);
        } else {
            RoutingRule rule = routingRuleStore.newRoutingRule()
                .name(routingRuleConfig.getName())
                .description(routingRuleConfig.getDescription())
                .mode(RoutingMode.valueOf(routingRuleConfig.getMode()))
                .matchers(routingRuleConfig.getMatchers());
            routingRuleStore.create(rule);
        }
    }
    /**
     * Apply all configs related to security
     *
     * @param security The security config
     */
    private void applySecurityConfig(ConfigSecurity security) {
        if (security.getAnonymousAccess() != null) {
            securityApi.setAnonymousAccess(security.getAnonymousAccess());
        }

        if (security.getRealms() != null) {
            security.getRealms().forEach(realm -> {
                if (realm.getEnabled() != null) {
                    if (realm.getEnabled()) {
                        log.info("Enabling realm {}", realm.getName());
                        realmManager.enableRealm(realm.getName(), true);
                    } else {
                        log.info("Disabling realm {}", realm.getName());
                        realmManager.disableRealm(realm.getName());
                    }
                } else {
                    log.warn("Passing a realm with enabled: null doesn't make sense...");
                }
            });
        }

        if (security.getPrivileges() != null) {
            List<ConfigSecurityPrivilege> privileges = security.getPrivileges();

            try {
                AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

                for (ConfigSecurityPrivilege p : privileges) {
                    if (p.isEnabled()) {
                        Privilege tmpPrivilege;
                        Boolean update = false;
                        try {
                            tmpPrivilege = authManager.getPrivilege(p.getId());
                            update = true;
                            tmpPrivilege.setName(p.getName());
                            tmpPrivilege.setDescription(p.getDescription());
                            tmpPrivilege.setType(p.getType());
                            tmpPrivilege.setProperties(p.getProperties());
                            tmpPrivilege.setReadOnly(p.getReadOnly());
                        } catch (NoSuchPrivilegeException e) {
                            tmpPrivilege = new Privilege(
                                    p.getId(),
                                    p.getName(),
                                    p.getDescription(),
                                    p.getType(),
                                    p.getProperties(),
                                    p.getReadOnly()
                            );
                        }

                        try {
                            if (update) {
                                log.info("Updating privilege {}", tmpPrivilege.getId());
                                tmpPrivilege = authManager.updatePrivilege(tmpPrivilege);
                            } else {
                                log.info("Creating privilege {}", tmpPrivilege.getId());
                                tmpPrivilege = authManager.addPrivilege(tmpPrivilege);
                            }
                        } catch (RuntimeException e) {
                            log.error("Failed to create/update permission {}", p.getId(), e);
                        }
                    } else {
                        log.info("Deleting privilege {}", p.getId());
                        authManager.deletePrivilege(p.getId());
                    }
                }
            } catch (NoSuchAuthorizationManagerException e) {
                log.error("AuthorizationManager {} does not exist.", "default", e);
            }
        }

        if (security.getRoles() != null) {
            boolean pruneRolesBySource = security.getPruneRolesBySource() == null || security.getPruneRolesBySource();
            Map<String, List<ConfigSecurityRole>> rolesBySource = security.getRoles().stream()
                    .collect(groupingBy(ConfigSecurityRole::getSource, toList()));
            for (Map.Entry<String, List<ConfigSecurityRole>> ent : rolesBySource.entrySet()) {
                String source = ent.getKey();
                List<ConfigSecurityRole> roles = ent.getValue();
                try {
                    AuthorizationManager authManager = securitySystem.getAuthorizationManager(source);
                    if (!authManager.supportsWrite())
                        throw new NotWritableException("AuthorizationManager: " + source);

                    // Remove roles first, so we fail fast if an enabled role references a removed one
                    Map<String, Boolean> idsToRemove = new HashMap<>();
                    if(pruneRolesBySource) {
                        authManager.listRoles().stream().map(Role::getRoleId).forEach(id -> idsToRemove.put(id, false));
                    }
                    for (ConfigSecurityRole role : roles) {
                        if (role.isEnabled()) {
                            idsToRemove.remove(role.getId());
                        } else {
                            idsToRemove.put(role.getId(), true);
                        }
                    }
                    for (Map.Entry<String, Boolean> toRemove : idsToRemove.entrySet()) {
                        log.info("Deleting role {}", toRemove.getKey());
                        try {
                            authManager.deleteRole(toRemove.getKey());
                        } catch(Exception e) {
                            if (toRemove.getValue()) {
                                throw e;
                            } else {
                                log.warn("Could not delete role {}: {}", toRemove.getKey(), e.getMessage());
                            }
                        }
                    }

                    for (ConfigSecurityRole r : roles) {
                        if (r.isEnabled()) {
                            Role tmpRole;
                            boolean update = false;
                            try {
                                tmpRole = authManager.getRole(r.getId());
                                update = true;
                            } catch (NoSuchRoleException e) {
                                tmpRole = new Role();
                                tmpRole.setRoleId(r.getId());
                                tmpRole.setSource(r.getSource());
                            }
                            tmpRole.setName(r.getName());
                            tmpRole.setDescription(r.getDescription());
                            tmpRole.setReadOnly(false);
                            tmpRole.setRoles(r.getRoles() == null ? null : new HashSet<>(r.getRoles()));
                            tmpRole.setPrivileges(r.getPrivileges() == null ? null : new HashSet<>(r.getPrivileges()));

                            try {
                                if (update) {
                                    log.info("Updating role {}", r.getId());
                                    authManager.updateRole(tmpRole);
                                } else {
                                    log.info("Creating role {}", r.getId());
                                    authManager.addRole(tmpRole);
                                }
                            } catch (RuntimeException e) {
                                log.error("Failed to create/update role {}", r.getId(), e);
                            }
                        }
                    }
                } catch (NoSuchAuthorizationManagerException e) {
                    log.error("AuthorizationManager {} does not exist.", source, e);
                } catch (NotWritableException e) {
                    log.error("AuthorizationManager {} is not writable", source, e);
                }
            }
        }

        if (security.getUsers() != null) {
            security.getUsers().forEach(userConfig -> {
                User existingUser = null;
                try {
                    existingUser = securitySystem.getUser(userConfig.getUsername());
                } catch (UserNotFoundException e) {
                    // ignore
                }

                if (existingUser != null) {
                    log.info("User {} already exists. Patching it...", userConfig.getUsername());
                    existingUser.setFirstName(userConfig.getFirstName());
                    existingUser.setLastName(userConfig.getLastName());
                    existingUser.setEmailAddress(userConfig.getEmail());

                    if (userConfig.getActive() != null) {
                        if (userConfig.getActive()) {
                            if (existingUser.getStatus() == UserStatus.disabled) {
                                log.info("Reactivating user {}", existingUser.getUserId());
                                existingUser.setStatus(UserStatus.active);
                            } else if (existingUser.getStatus() != UserStatus.active) {
                                log.error("Can not activate user {} ({}) with state {}", existingUser.getUserId(), existingUser.getSource(), existingUser.getStatus());
                            }
                        } else {
                            if (existingUser.getStatus() != UserStatus.disabled) {
                                log.info("Disabling user {} ({}) with state {}", existingUser.getUserId(), existingUser.getSource(), existingUser.getStatus());
                                existingUser.setStatus(UserStatus.disabled);
                            }
                        }
                    }

                    if (userConfig.getUpdateExistingPassword() != null && userConfig.getUpdateExistingPassword()) {
                        try {
                            ThreadContext.bind(FakeAlmightySubject.forUserId("nexus:*"));
                            securitySystem.changePassword(existingUser.getUserId(), userConfig.getPassword());
                        } catch (UserNotFoundException e) {
                            log.error("Failed to update password of user {}", existingUser.getUserId(), e);
                        } finally {
                            ThreadContext.remove();
                        }
                    }

                    existingUser.setRoles(userConfig.getRoles().stream().map(r -> new RoleIdentifier(r.getSource(), r.getRole())).collect(Collectors.toSet()));
                    try {
                        securitySystem.updateUser(existingUser);
                    } catch (UserNotFoundException | NoSuchUserManagerException e) {
                        log.error("Could not update user {}", userConfig.getUsername(), e);
                    }
                } else {
                    log.info("User {} does not yet exist. Creating it...", userConfig.getUsername());
                    securityApi.addUser(
                            userConfig.getUsername(),
                            userConfig.getFirstName(),
                            userConfig.getLastName(),
                            userConfig.getEmail(),
                            userConfig.getActive() != null ? userConfig.getActive() : true,
                            userConfig.getPassword(),
                            userConfig.getRoles().stream().map(ConfigSecurityUserRole::getRole).collect(toList())
                    );
                }
            });

            if (security.getPruneUsers() != null && security.getPruneUsers()) {
                Set<User> existingUsers = securitySystem.searchUsers(new UserSearchCriteria());

                existingUsers.forEach(existingUser -> {
                    if (security.getUsers().stream().noneMatch(u -> existingUser.getUserId().equals(u.getUsername()))) {
                        log.info("Pruning user {} ...", existingUser.getUserId());
                        try {
                            securitySystem.deleteUser(existingUser.getUserId(), existingUser.getSource());
                        } catch (NoSuchUserManagerException | UserNotFoundException e) {
                            log.error("Failed to prune user {} ({})", existingUser.getUserId(), existingUser.getSource(), e);
                        }
                    }
                });
            }
        } else if (security.getPruneUsers() != null && security.getPruneUsers()) {
            log.error("security.pruneUsers has no effect when not specifying any users!");
        }
    }
}

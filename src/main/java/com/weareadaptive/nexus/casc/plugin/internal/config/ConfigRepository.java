package com.weareadaptive.nexus.casc.plugin.internal.config;

import java.util.List;

public class ConfigRepository {
    private Boolean pruneBlobStores;
    private List<ConfigBlobStore> blobStores;
    private Boolean pruneCleanupPolicies;
    private List<ConfigCleanupPolicy> cleanupPolicies;
    private Boolean pruneRepositories;
    private List<ConfigRepositoryEntry> repositories;

    private List<ConfigRoutingRule> routingRules;

    public Boolean getPruneBlobStores() {
        return pruneBlobStores;
    }

    public void setPruneBlobStores(Boolean pruneBlobStores) {
        this.pruneBlobStores = pruneBlobStores;
    }

    public List<ConfigBlobStore> getBlobStores() {
        return blobStores;
    }

    public void setBlobStores(List<ConfigBlobStore> blobStores) {
        this.blobStores = blobStores;
    }

    public Boolean getPruneCleanupPolicies() {
        return pruneCleanupPolicies;
    }

    public void setPruneCleanupPolicies(Boolean pruneCleanupPolicies) {
        this.pruneCleanupPolicies = pruneCleanupPolicies;
    }

    public List<ConfigCleanupPolicy> getCleanupPolicies() {
        return cleanupPolicies;
    }

    public void setCleanupPolicies(List<ConfigCleanupPolicy> cleanupPolicies) {
        this.cleanupPolicies = cleanupPolicies;
    }

    public Boolean getPruneRepositories() {
        return pruneRepositories;
    }

    public void setPruneRepositories(Boolean pruneRepositories) {
        this.pruneRepositories = pruneRepositories;
    }

    public List<ConfigRepositoryEntry> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<ConfigRepositoryEntry> repositories) {
        this.repositories = repositories;
    }

    public List<ConfigRoutingRule> getRoutingRules() {
        return routingRules;
    }

    public void setRoutingRules(List<ConfigRoutingRule> routingRules) {
        this.routingRules = routingRules;
    }

}

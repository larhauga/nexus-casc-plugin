package com.weareadaptive.nexus.casc.plugin.internal.config;

import org.sonatype.nexus.common.entity.EntityId;

import java.util.HashMap;
import java.util.Map;

public class ConfigRepositoryEntry {
    private String name;
    private String recipeName;
    private Boolean online;
    private Map<String, Map<String, Object>> attributes = new HashMap<>();

    private String routingRuleName;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRecipeName() {
        return recipeName;
    }

    public void setRecipeName(String recipeName) {
        this.recipeName = recipeName;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Map<String, Map<String, Object>> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Map<String, Object>> attributes) {
        this.attributes = attributes;
    }
    public String getRoutingRuleName() {
        return routingRuleName;
    }

    public void setRoutingRuleName(String routingRuleName) {
        this.routingRuleName = routingRuleName;
    }

}

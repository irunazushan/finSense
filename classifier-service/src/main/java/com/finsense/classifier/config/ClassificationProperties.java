package com.finsense.classifier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.classification")
public class ClassificationProperties {

    private String rulesFile = "file:./classifier-rules.yaml";
    private String strategy = "rule";
    private final Model model = new Model();

    public String getRulesFile() {
        return rulesFile;
    }

    public void setRulesFile(String rulesFile) {
        this.rulesFile = rulesFile;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Model getModel() {
        return model;
    }

    public static class Model {

        private String dir = "./model";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}

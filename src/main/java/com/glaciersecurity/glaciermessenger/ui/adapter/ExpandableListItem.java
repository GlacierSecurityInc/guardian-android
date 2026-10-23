package com.glaciersecurity.glaciermessenger.ui.adapter;

import java.util.Objects;

public class ExpandableListItem {

    public int image;
    public String name;
    public boolean isExpandable = false;
    public boolean isMajorIssue = false;
    public boolean parent = false;
    public String description;
    public String expandedDescription;
    public boolean isExpanded = false;

    public ExpandableListItem(int image, String name, String description) {
        this.image = image;
        this.name = name;
        this.description = description;
        this.isExpandable = false;
        this.isMajorIssue = false;
    }
    public ExpandableListItem(int image, String name, String description, boolean isMajorIssue) {
        this.image = image;
        this.name = name;
        this.description = description;
        this.isExpandable = false;
        this.isMajorIssue = isMajorIssue;
    }
    public ExpandableListItem(int image, String name, String description, boolean expanded, boolean isMajorIssue) {
        this.image = image;
        this.name = name;
        this.description = description;
        this.isExpandable = expanded;
        this.isMajorIssue = isMajorIssue;
    }

    public ExpandableListItem withExpandedDescription(String expandedDescription) {
        this.expandedDescription = expandedDescription;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExpandableListItem that = (ExpandableListItem) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

}

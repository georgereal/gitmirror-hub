package com.gitutility.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RepositoryRulesetItem {
    private long id;
    private String name;
    private String target;
    private String enforcement;
    /** True when this row is the Hub replica ruleset {@code gitmirror-replica-readonly}. */
    private boolean hubReplica;
}

package com.dairy.milkroute.enums;

/**
 * How much to trust a merge proposal's numbers.
 *
 * <p>Walking distances are straight-line, so every proposal needs field validation before
 * anyone acts on it. This grades how far off the estimate might be.
 */
public enum MergeConfidence {
    HIGH,
    MEDIUM,
    LOW
}

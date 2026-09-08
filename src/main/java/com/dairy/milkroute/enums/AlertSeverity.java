package com.dairy.milkroute.enums;

/**
 * Alert severity. Mirrors chk_alert_severity.
 *
 * <p>Severity is part of the dedupe key, so a WARNING escalating to CRITICAL raises a new
 * alert while a WARNING repeating every sixty seconds does not.
 */
public enum AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

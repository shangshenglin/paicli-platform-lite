package com.paicli.platform.server.tool;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class CommandApprovalPolicy {
    private static final List<Rule> RULES = List.of(
            rule("file deletion or destructive disk operation",
                    "(^|[\\s;&|()])(?:rm|rmdir|del|erase|remove-item|clear-content|shred|unlink|ri|rd|format|mkfs(?:\\.\\w+)?|diskpart|dd)(?=$|[\\s;&|()])"),
            rule("privilege or permission modification",
                    "(^|[\\s;&|()])(?:sudo|su|runas|chmod|chown|icacls|takeown|set-executionpolicy)(?=$|[\\s;&|()])"),
            rule("process, service, or machine control",
                    "(^|[\\s;&|()])(?:kill|pkill|killall|taskkill|stop-process|stop-service|restart-service|shutdown|reboot|restart-computer)(?=$|[\\s;&|()])|\\bsc(?:\\.exe)?\\s+(?:stop|delete)\\b"),
            rule("destructive or remote version-control operation",
                    "\\bgit\\s+(?:(?:-[^\\s]+)\\s+)*(?:push|clean|reset)\\b|\\bgit\\s+checkout\\s+--(?:\\s|$)|\\bgit\\s+restore\\b|\\bgit\\s+branch\\s+-d\\b"),
            rule("package installation, publishing, or deployment",
                    "\\b(?:npm|pnpm|yarn)\\s+(?:install|add|publish)\\b|\\b(?:pip|pip3)\\s+install\\b|\\b(?:apt|apt-get|dnf|yum|pacman|choco|winget)\\s+(?:install|remove|uninstall|upgrade)\\b|\\bmvn(?:w(?:\\.cmd)?)?\\b[^;&|]*(?:\\s|^)(?:deploy)(?:\\s|$)|\\bgradle(?:w(?:\\.bat)?)?\\b[^;&|]*\\bpublish\\b|\\bdocker\\s+(?:push|rm|rmi|kill|stop|prune|system\\s+prune)\\b|\\bkubectl\\b[^;&|]*\\b(?:apply|delete|patch|replace|scale)\\b|\\bterraform\\s+(?:apply|destroy)\\b"),
            rule("network download or remote command",
                    "(^|[\\s;&|()])(?:curl|wget|invoke-webrequest|invoke-restmethod|iwr|irm|ssh|scp|sftp|rsync)(?=$|[\\s;&|()])"),
            rule("dynamic or encoded command execution",
                    "(^|[\\s;&|()])(?:invoke-expression|iex|eval)(?=$|[\\s;&|()])|(?:-|/)encodedcommand\\b|frombase64string\\s*\\("),
            rule("destructive database statement",
                    "\\b(?:drop|truncate)\\s+(?:table|database|schema)\\b|\\bdelete\\s+from\\b")
    );

    private CommandApprovalPolicy() {
    }

    static Optional<String> approvalReason(Object commandValue) {
        String command = commandValue == null ? "" : String.valueOf(commandValue).trim();
        if (command.isBlank()) return Optional.of("empty or missing command");
        String normalized = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return RULES.stream()
                .filter(rule -> rule.pattern().matcher(normalized).find())
                .map(Rule::reason)
                .findFirst();
    }

    private static Rule rule(String reason, String regex) {
        return new Rule(reason, Pattern.compile(regex));
    }

    private record Rule(String reason, Pattern pattern) {
    }
}

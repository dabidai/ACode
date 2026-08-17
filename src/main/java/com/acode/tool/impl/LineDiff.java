package com.acode.tool.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行级 diff：先裁共同前缀/后缀，仅对变更中段做 LCS 回溯，产出 `"- "` / `"+ "` 前缀行列表。
 * 文件整体多大不影响是否出 diff——只有「变更本身过大」（oldMid+newMid &gt; maxChange）才返回 null 让调用方降级。
 */
final class LineDiff {

    private LineDiff() {
    }

    /**
     * @param old       旧内容按行拆分（调用方负责保留原行内容）
     * @param current   新内容按行拆分
     * @param maxChange 变更中段行数上限；oldMid.size() + newMid.size() 超限返回 null
     * @return `"- "` / `"+ "` 前缀行列表；完全相同返回空列表；变更超限返回 null
     */
    static List<String> diffLines(List<String> old, List<String> current, int maxChange) {
        int prefix = 0;
        while (prefix < old.size() && prefix < current.size()
                && old.get(prefix).equals(current.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < old.size() - prefix && suffix < current.size() - prefix
                && old.get(old.size() - 1 - suffix).equals(current.get(current.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> oldMid = old.subList(prefix, old.size() - suffix);
        List<String> newMid = current.subList(prefix, current.size() - suffix);
        if (oldMid.isEmpty() && newMid.isEmpty()) {
            return List.of();
        }
        if (oldMid.size() + newMid.size() > maxChange) {
            return null;
        }

        int o = oldMid.size();
        int n = newMid.size();
        int[][] dp = new int[o + 1][n + 1];
        for (int i = 1; i <= o; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldMid.get(i - 1).equals(newMid.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        List<String> diff = new ArrayList<>();
        int i = o;
        int j = n;
        while (i > 0 && j > 0) {
            if (oldMid.get(i - 1).equals(newMid.get(j - 1))) {
                i--;
                j--;
            } else if (dp[i][j - 1] >= dp[i - 1][j]) {
                diff.add("+ " + newMid.get(j - 1));
                j--;
            } else {
                diff.add("- " + oldMid.get(i - 1));
                i--;
            }
        }
        while (i > 0) {
            diff.add("- " + oldMid.get(i - 1));
            i--;
        }
        while (j > 0) {
            diff.add("+ " + newMid.get(j - 1));
            j--;
        }
        Collections.reverse(diff);
        return diff;
    }
}

package com.xaoxiao.convenientaccess.whitelist;

import java.util.HashMap;
import java.util.Map;

/**
 * 白名单统计信息
 */
public class WhitelistStats {
    private int totalPlayers;
    private int activePlayers;
    private Map<String, Integer> sourceCounts = new HashMap<>();
    private int recentAdditions;
    private String growthTrend = "stable";
    
    public WhitelistStats() {}
    
    public int getTotalPlayers() {
        return totalPlayers;
    }
    
    public void setTotalPlayers(int totalPlayers) {
        this.totalPlayers = totalPlayers;
    }
    
    public int getActivePlayers() {
        return activePlayers;
    }
    
    public void setActivePlayers(int activePlayers) {
        this.activePlayers = activePlayers;
    }
    
    public Map<String, Integer> getSourceCounts() {
        return sourceCounts;
    }
    
    public void setSourceCounts(Map<String, Integer> sourceCounts) {
        this.sourceCounts = sourceCounts;
    }
    
    public void addSourceCount(String source, int count) {
        this.sourceCounts.put(source, count);
    }
    
    public int getRecentAdditions() {
        return recentAdditions;
    }
    
    public void setRecentAdditions(int recentAdditions) {
        this.recentAdditions = recentAdditions;
    }
    
    public String getGrowthTrend() {
        return growthTrend;
    }
    
    public void setGrowthTrend(String growthTrend) {
        this.growthTrend = growthTrend;
    }
    
    @Override
    public String toString() {
        return "WhitelistStats{" +
                "totalPlayers=" + totalPlayers +
                ", activePlayers=" + activePlayers +
                ", sourceCounts=" + sourceCounts +
                ", recentAdditions=" + recentAdditions +
                ", growthTrend='" + growthTrend + '\'' +
                '}';
    }
}
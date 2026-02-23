package com.turbovnc.vncviewer.network;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

public class IntelligentConnectionManager {
    private final DeviceDiscoveryService discoveryService;
    private final Map<String, DeviceProfile> deviceProfiles;
    private final ExecutorService connectionExecutor;
    private final ConnectionHistoryManager historyManager;
    
    public static class DeviceProfile {
        private final String deviceId;
        private final String deviceName;
        private final String deviceType;
        private final String preferredConnectionMethod;
        private final Map<String, Object> connectionSettings;
        private final boolean autoConnect;
        private final int connectionPriority;
        
        public DeviceProfile(String deviceId, String deviceName, String deviceType) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.deviceType = deviceType;
            this.preferredConnectionMethod = "VNC";
            this.connectionSettings = new HashMap<>();
            this.autoConnect = false;
            this.connectionPriority = 5; // 1-10 scale, 10 being highest
        }
        
        public String getDeviceId() { return deviceId; }
        public String getDeviceName() { return deviceName; }
        public String getDeviceType() { return deviceType; }
        public String getPreferredConnectionMethod() { return preferredConnectionMethod; }
        public Map<String, Object> getConnectionSettings() { return connectionSettings; }
        public boolean isAutoConnect() { return autoConnect; }
        public int getConnectionPriority() { return connectionPriority; }
        
        public void setConnectionSetting(String key, Object value) {
            connectionSettings.put(key, value);
        }
        
        public Object getConnectionSetting(String key) {
            return connectionSettings.get(key);
        }
    }
    
    public static class ConnectionRecommendation {
        private final DeviceDiscoveryService.DiscoveredDevice device;
        private final double confidenceScore;
        private final String recommendationReason;
        private final Map<String, Object> suggestedSettings;
        
        public ConnectionRecommendation(DeviceDiscoveryService.DiscoveredDevice device, 
                                      double confidenceScore, String recommendationReason) {
            this.device = device;
            this.confidenceScore = confidenceScore;
            this.recommendationReason = recommendationReason;
            this.suggestedSettings = new HashMap<>();
        }
        
        public DeviceDiscoveryService.DiscoveredDevice getDevice() { return device; }
        public double getConfidenceScore() { return confidenceScore; }
        public String getRecommendationReason() { return recommendationReason; }
        public Map<String, Object> getSuggestedSettings() { return suggestedSettings; }
        
        public void addSuggestedSetting(String key, Object value) {
            suggestedSettings.put(key, value);
        }
    }
    
    public IntelligentConnectionManager() {
        this.discoveryService = new DeviceDiscoveryService();
        this.deviceProfiles = new ConcurrentHashMap<>();
        this.connectionExecutor = Executors.newCachedThreadPool();
        this.historyManager = new ConnectionHistoryManager();
        
        setupAutoDiscovery();
    }
    
    private void setupAutoDiscovery() {
        discoveryService.addDiscoveryListener(new DeviceDiscoveryService.DeviceDiscoveryListener() {
            @Override
            public void onDeviceDiscovered(DeviceDiscoveryService.DiscoveredDevice device) {
                analyzeAndRecommendConnection(device);
            }
            
            @Override
            public void onDeviceLost(DeviceDiscoveryService.DiscoveredDevice device) {
                // Handle device loss
            }
            
            @Override
            public void onDiscoveryStarted() {
                // Handle discovery start
            }
            
            @Override
            public void onDiscoveryCompleted() {
                // Handle discovery completion
            }
        });
    }
    
    public List<ConnectionRecommendation> getConnectionRecommendations() {
        List<ConnectionRecommendation> recommendations = new ArrayList<>();
        Set<DeviceDiscoveryService.DiscoveredDevice> devices = discoveryService.getDiscoveredDevices();
        
        for (DeviceDiscoveryService.DiscoveredDevice device : devices) {
            ConnectionRecommendation recommendation = analyzeDeviceForConnection(device);
            if (recommendation != null && recommendation.getConfidenceScore() > 0.7) {
                recommendations.add(recommendation);
            }
        }
        
        // Sort by confidence score
        recommendations.sort((a, b) -> Double.compare(b.getConfidenceScore(), a.getConfidenceScore()));
        
        return recommendations;
    }
    
    private ConnectionRecommendation analyzeDeviceForConnection(DeviceDiscoveryService.DiscoveredDevice device) {
        double confidenceScore = 0.0;
        StringBuilder reason = new StringBuilder();
        
        // Check if device has been connected before
        if (historyManager.hasConnectedTo(device.getIpAddress())) {
            confidenceScore += 0.4;
            reason.append("Previously connected device. ");
        }
        
        // Analyze device type
        String deviceType = device.getDeviceType();
        if ("Desktop".equals(deviceType) || "Laptop".equals(deviceType)) {
            confidenceScore += 0.2;
            reason.append("Desktop/Laptop device type. ");
        } else if ("Server".equals(deviceType)) {
            confidenceScore += 0.3;
            reason.append("Server device type. ");
        }
        
        // Check port accessibility
        if (isPortAccessible(device.getIpAddress(), device.getVncPort())) {
            confidenceScore += 0.3;
            reason.append("VNC port accessible. ");
        }
        
        // Check for known device profiles
        String deviceId = generateDeviceId(device);
        if (deviceProfiles.containsKey(deviceId)) {
            confidenceScore += 0.1;
            reason.append("Has device profile. ");
        }
        
        if (confidenceScore > 0.0) {
            ConnectionRecommendation recommendation = new ConnectionRecommendation(
                device, confidenceScore, reason.toString().trim());
            
            // Add suggested settings based on analysis
            addSuggestedSettings(recommendation, device);
            
            return recommendation;
        }
        
        return null;
    }
    
    private void analyzeAndRecommendConnection(DeviceDiscoveryService.DiscoveredDevice device) {
        ConnectionRecommendation recommendation = analyzeDeviceForConnection(device);
        
        if (recommendation != null && recommendation.getConfidenceScore() > 0.8) {
            // High confidence recommendation - could auto-connect or show prominent notification
            SwingUtilities.invokeLater(() -> {
                showConnectionRecommendation(recommendation);
            });
        }
    }
    
    private void showConnectionRecommendation(ConnectionRecommendation recommendation) {
        DeviceDiscoveryService.DiscoveredDevice device = recommendation.getDevice();
        
        String message = String.format(
            "High-confidence connection recommendation:\n\n" +
            "Device: %s (%s)\n" +
            "IP: %s:%d\n" +
            "Confidence: %.1f%%\n" +
            "Reason: %s\n\n" +
            "Would you like to connect to this device?",
            device.getHostname(), device.getDeviceType(),
            device.getIpAddress(), device.getVncPort(),
            recommendation.getConfidenceScore() * 100,
            recommendation.getRecommendationReason()
        );
        
        int result = JOptionPane.showConfirmDialog(
            null, message, "Connection Recommendation", 
            JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        
        if (result == JOptionPane.YES_OPTION) {
            initiateConnection(device, recommendation.getSuggestedSettings());
        }
    }
    
    public void initiateConnection(DeviceDiscoveryService.DiscoveredDevice device, Map<String, Object> settings) {
        connectionExecutor.submit(() -> {
            try {
                log("Initiating connection to " + device.getHostname() + " at " + device.getIpAddress() + ":" + device.getVncPort());
                
                // Store connection attempt in history
                historyManager.recordConnectionAttempt(device.getIpAddress(), true);
                
                // Here you would integrate with the existing VNC connection logic
                // For now, we'll simulate the connection process
                
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null,
                        "Connection to " + device.getHostname() + " initiated successfully!\n" +
                        "IP: " + device.getIpAddress() + ":" + device.getVncPort() + "\n" +
                        "This would open the VNC viewer for the selected device.",
                        "Connection Initiated",
                        JOptionPane.INFORMATION_MESSAGE);
                });
                
            } catch (Exception e) {
                log("Connection failed: " + e.getMessage());
                historyManager.recordConnectionAttempt(device.getIpAddress(), false);
            }
        });
    }
    
    private boolean isPortAccessible(String ipAddress, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ipAddress, port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    private String generateDeviceId(DeviceDiscoveryService.DiscoveredDevice device) {
        return device.getIpAddress() + ":" + device.getVncPort();
    }
    
    private void addSuggestedSettings(ConnectionRecommendation recommendation, DeviceDiscoveryService.DiscoveredDevice device) {
        // Add compression settings based on device type
        if ("Mobile".equals(device.getDeviceType())) {
            recommendation.addSuggestedSetting("compression", "high");
            recommendation.addSuggestedSetting("color_depth", "8");
        } else if ("Desktop".equals(device.getDeviceType()) || "Laptop".equals(device.getDeviceType())) {
            recommendation.addSuggestedSetting("compression", "medium");
            recommendation.addSuggestedSetting("color_depth", "24");
        }
        
        // Add security settings
        recommendation.addSuggestedSetting("encryption", "preferred");
        recommendation.addSuggestedSetting("authentication", "required");
        
        // Add performance settings based on OS
        String os = device.getOperatingSystem();
        if ("Windows".equals(os)) {
            recommendation.addSuggestedSetting("encoding", "Tight");
        } else if ("Linux".equals(os)) {
            recommendation.addSuggestedSetting("encoding", "ZRLE");
        } else if ("macOS".equals(os)) {
            recommendation.addSuggestedSetting("encoding", "Tight");
        }
    }
    
    public DeviceDiscoveryService getDiscoveryService() {
        return discoveryService;
    }
    
    public void startDiscovery() {
        discoveryService.startDiscovery();
    }
    
    public void stopDiscovery() {
        discoveryService.stopDiscovery();
    }
    
    private void log(String message) {
        System.out.println("[IntelligentConnectionManager] " + message);
    }
    
    private static class ConnectionHistoryManager {
        private final Map<String, ConnectionHistory> connectionHistory = new ConcurrentHashMap<>();
        
        public void recordConnectionAttempt(String ipAddress, boolean success) {
            ConnectionHistory history = connectionHistory.computeIfAbsent(ipAddress, k -> new ConnectionHistory());
            history.addAttempt(success);
        }
        
        public boolean hasConnectedTo(String ipAddress) {
            ConnectionHistory history = connectionHistory.get(ipAddress);
            return history != null && history.getSuccessfulConnections() > 0;
        }
        
        public int getConnectionCount(String ipAddress) {
            ConnectionHistory history = connectionHistory.get(ipAddress);
            return history != null ? history.getTotalAttempts() : 0;
        }
        
        private static class ConnectionHistory {
            private int successfulConnections = 0;
            private int totalAttempts = 0;
            private long lastConnectionTime = 0;
            
            public void addAttempt(boolean success) {
                totalAttempts++;
                if (success) {
                    successfulConnections++;
                    lastConnectionTime = System.currentTimeMillis();
                }
            }
            
            public int getSuccessfulConnections() {
                return successfulConnections;
            }
            
            public int getTotalAttempts() {
                return totalAttempts;
            }
            
            public long getLastConnectionTime() {
                return lastConnectionTime;
            }
        }
    }
}
package com.turbovnc.vncviewer.network;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SecureDeviceAuthentication {
    
    public static class DeviceCredentials {
        private final String deviceId;
        private final String publicKeyFingerprint;
        private final byte[] encryptedCredentials;
        private final long timestamp;
        private final int trustLevel; // 0-100 scale
        
        public DeviceCredentials(String deviceId, String publicKeyFingerprint, byte[] encryptedCredentials) {
            this.deviceId = deviceId;
            this.publicKeyFingerprint = publicKeyFingerprint;
            this.encryptedCredentials = encryptedCredentials;
            this.timestamp = System.currentTimeMillis();
            this.trustLevel = 0;
        }
        
        public String getDeviceId() { return deviceId; }
        public String getPublicKeyFingerprint() { return publicKeyFingerprint; }
        public byte[] getEncryptedCredentials() { return encryptedCredentials; }
        public long getTimestamp() { return timestamp; }
        public int getTrustLevel() { return trustLevel; }
    }
    
    public static class AuthenticationResult {
        private final boolean success;
        private final String deviceId;
        private final String message;
        private final int trustLevel;
        
        public AuthenticationResult(boolean success, String deviceId, String message, int trustLevel) {
            this.success = success;
            this.deviceId = deviceId;
            this.message = message;
            this.trustLevel = trustLevel;
        }
        
        public boolean isSuccess() { return success; }
        public String getDeviceId() { return deviceId; }
        public String getMessage() { return message; }
        public int getTrustLevel() { return trustLevel; }
    }
    
    private final Map<String, DeviceCredentials> trustedDevices;
    private final Map<String, Integer> deviceTrustLevels;
    private final SecureRandom secureRandom;
    private final KeyPairGenerator keyPairGenerator;
    private KeyPair serverKeyPair;
    
    public SecureDeviceAuthentication() throws NoSuchAlgorithmException {
        this.trustedDevices = new ConcurrentHashMap<>();
        this.deviceTrustLevels = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
        this.keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        this.keyPairGenerator.initialize(2048);
        
        generateServerKeyPair();
    }
    
    private void generateServerKeyPair() {
        try {
            this.serverKeyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate server key pair", e);
        }
    }
    
    public String getServerPublicKeyFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] publicKeyBytes = serverKeyPair.getPublic().getEncoded();
            byte[] fingerprint = md.digest(publicKeyBytes);
            return bytesToHex(fingerprint);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    public AuthenticationResult authenticateDevice(String deviceId, String devicePublicKey, byte[] credentials) {
        try {
            // Validate device ID format
            if (!isValidDeviceId(deviceId)) {
                return new AuthenticationResult(false, deviceId, "Invalid device ID format", 0);
            }
            
            // Check if device is already trusted
            if (trustedDevices.containsKey(deviceId)) {
                DeviceCredentials existingCreds = trustedDevices.get(deviceId);
                int trustLevel = deviceTrustLevels.getOrDefault(deviceId, 0);
                
                if (verifyExistingDevice(deviceId, devicePublicKey, credentials)) {
                    return new AuthenticationResult(true, deviceId, "Device authenticated successfully", trustLevel);
                } else {
                    return new AuthenticationResult(false, deviceId, "Device credentials verification failed", 0);
                }
            }
            
            // New device - perform full authentication
            return authenticateNewDevice(deviceId, devicePublicKey, credentials);
            
        } catch (Exception e) {
            return new AuthenticationResult(false, deviceId, "Authentication error: " + e.getMessage(), 0);
        }
    }
    
    private AuthenticationResult authenticateNewDevice(String deviceId, String devicePublicKey, byte[] credentials) {
        try {
            // Generate device fingerprint
            String deviceFingerprint = generateDeviceFingerprint(devicePublicKey);
            
            // Verify device credentials
            if (!verifyDeviceCredentials(deviceId, devicePublicKey, credentials)) {
                return new AuthenticationResult(false, deviceId, "Invalid device credentials", 0);
            }
            
            // Calculate initial trust level based on various factors
            int trustLevel = calculateInitialTrustLevel(deviceId, devicePublicKey);
            
            // Store device credentials
            DeviceCredentials deviceCreds = new DeviceCredentials(deviceId, deviceFingerprint, credentials);
            trustedDevices.put(deviceId, deviceCreds);
            deviceTrustLevels.put(deviceId, trustLevel);
            
            return new AuthenticationResult(true, deviceId, "New device authenticated and trusted", trustLevel);
            
        } catch (Exception e) {
            return new AuthenticationResult(false, deviceId, "New device authentication failed: " + e.getMessage(), 0);
        }
    }
    
    private boolean verifyExistingDevice(String deviceId, String devicePublicKey, byte[] credentials) {
        DeviceCredentials existingCreds = trustedDevices.get(deviceId);
        if (existingCreds == null) return false;
        
        String currentFingerprint = generateDeviceFingerprint(devicePublicKey);
        return existingCreds.getPublicKeyFingerprint().equals(currentFingerprint);
    }
    
    private boolean verifyDeviceCredentials(String deviceId, String devicePublicKey, byte[] credentials) {
        try {
            // Decrypt credentials using server private key
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, serverKeyPair.getPrivate());
            byte[] decryptedCredentials = cipher.doFinal(credentials);
            
            // Verify credentials contain expected device information
            String decryptedData = new String(decryptedCredentials, "UTF-8");
            return decryptedData.contains(deviceId) && decryptedData.contains(devicePublicKey.substring(0, 20));
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private int calculateInitialTrustLevel(String deviceId, String devicePublicKey) {
        int trustLevel = 30; // Base trust level for new devices
        
        // Increase trust based on device characteristics
        if (deviceId.length() > 10) trustLevel += 10;
        if (devicePublicKey.length() > 100) trustLevel += 10;
        
        // Check if device is on local network
        if (isLocalNetworkDevice(deviceId)) trustLevel += 20;
        
        // Add random component to prevent predictable trust levels
        trustLevel += secureRandom.nextInt(20);
        
        return Math.min(trustLevel, 80); // Cap at 80 for new devices
    }
    
    private boolean isLocalNetworkDevice(String deviceId) {
        // Simple check for local network devices
        return deviceId.contains("192.168.") || deviceId.contains("10.") || deviceId.contains("172.");
    }
    
    private String generateDeviceFingerprint(String publicKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] publicKeyBytes = publicKey.getBytes("UTF-8");
            byte[] fingerprint = md.digest(publicKeyBytes);
            return bytesToHex(fingerprint);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate device fingerprint", e);
        }
    }
    
    private boolean isValidDeviceId(String deviceId) {
        return deviceId != null && !deviceId.trim().isEmpty() && deviceId.length() <= 100;
    }
    
    public void updateDeviceTrust(String deviceId, int trustChange) {
        Integer currentTrust = deviceTrustLevels.get(deviceId);
        if (currentTrust != null) {
            int newTrust = Math.max(0, Math.min(100, currentTrust + trustChange));
            deviceTrustLevels.put(deviceId, newTrust);
        }
    }
    
    public void revokeDeviceTrust(String deviceId) {
        trustedDevices.remove(deviceId);
        deviceTrustLevels.remove(deviceId);
    }
    
    public Set<String> getTrustedDevices() {
        return new HashSet<>(trustedDevices.keySet());
    }
    
    public int getDeviceTrustLevel(String deviceId) {
        return deviceTrustLevels.getOrDefault(deviceId, 0);
    }
    
    public boolean isDeviceTrusted(String deviceId) {
        return trustedDevices.containsKey(deviceId) && 
               deviceTrustLevels.getOrDefault(deviceId, 0) >= 50;
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    public byte[] generateChallenge(String deviceId) {
        try {
            byte[] challenge = new byte[32];
            secureRandom.nextBytes(challenge);
            return challenge;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate challenge", e);
        }
    }
    
    public boolean verifyChallengeResponse(String deviceId, byte[] challenge, byte[] response) {
        try {
            DeviceCredentials deviceCreds = trustedDevices.get(deviceId);
            if (deviceCreds == null) return false;
            
            // Simple challenge-response verification
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(challenge);
            md.update(deviceCreds.getPublicKeyFingerprint().getBytes("UTF-8"));
            byte[] expectedResponse = md.digest();
            
            return MessageDigest.isEqual(expectedResponse, response);
            
        } catch (Exception e) {
            return false;
        }
    }
}
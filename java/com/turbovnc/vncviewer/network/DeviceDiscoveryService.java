package com.turbovnc.vncviewer.network;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

public class DeviceDiscoveryService {
    private static final int DISCOVERY_PORT = 5900;
    private static final int DISCOVERY_TIMEOUT = 5000;
    private static final String DISCOVERY_MAGIC = "SKYNET_DISCOVERY";
    
    private final Set<DiscoveredDevice> discoveredDevices = ConcurrentHashMap.newKeySet();
    private final List<DeviceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService discoveryExecutor = Executors.newCachedThreadPool();
    private volatile boolean isDiscovering = false;
    
    public interface DeviceDiscoveryListener {
        void onDeviceDiscovered(DiscoveredDevice device);
        void onDeviceLost(DiscoveredDevice device);
        void onDiscoveryStarted();
        void onDiscoveryCompleted();
    }
    
    public static class DiscoveredDevice {
        private final String ipAddress;
        private final String hostname;
        private final int vncPort;
        private final String deviceType;
        private final String operatingSystem;
        private final long discoveryTime;
        private final Map<String, String> metadata;
        
        public DiscoveredDevice(String ipAddress, String hostname, int vncPort, 
                              String deviceType, String operatingSystem) {
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.vncPort = vncPort;
            this.deviceType = deviceType;
            this.operatingSystem = operatingSystem;
            this.discoveryTime = System.currentTimeMillis();
            this.metadata = new HashMap<>();
        }
        
        public String getIpAddress() { return ipAddress; }
        public String getHostname() { return hostname; }
        public int getVncPort() { return vncPort; }
        public String getDeviceType() { return deviceType; }
        public String getOperatingSystem() { return operatingSystem; }
        public long getDiscoveryTime() { return discoveryTime; }
        public Map<String, String> getMetadata() { return metadata; }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            DiscoveredDevice that = (DiscoveredDevice) obj;
            return vncPort == that.vncPort && 
                   Objects.equals(ipAddress, that.ipAddress);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(ipAddress, vncPort);
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - %s:%d", hostname, deviceType, ipAddress, vncPort);
        }
    }
    
    public void addDiscoveryListener(DeviceDiscoveryListener listener) {
        listeners.add(listener);
    }
    
    public void removeDiscoveryListener(DeviceDiscoveryListener listener) {
        listeners.remove(listener);
    }
    
    public void startDiscovery() {
        if (isDiscovering) return;
        
        isDiscovering = true;
        discoveredDevices.clear();
        
        SwingUtilities.invokeLater(() -> {
            for (DeviceDiscoveryListener listener : listeners) {
                listener.onDiscoveryStarted();
            }
        });
        
        discoveryExecutor.submit(this::performNetworkScan);
        discoveryExecutor.submit(this::performMDNSDiscovery);
        discoveryExecutor.submit(this::performSSDPScan);
    }
    
    public void stopDiscovery() {
        isDiscovering = false;
        discoveryExecutor.shutdown();
        
        SwingUtilities.invokeLater(() -> {
            for (DeviceDiscoveryListener listener : listeners) {
                listener.onDiscoveryCompleted();
            }
        });
    }
    
    private void performNetworkScan() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements() && isDiscovering) {
                NetworkInterface networkInterface = interfaces.nextElement();
                
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null) {
                        scanNetworkRange(broadcast, interfaceAddress.getNetworkPrefixLength());
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error during network scanning: " + e.getMessage());
        }
    }
    
    private void scanNetworkRange(InetAddress broadcast, int prefixLength) {
        byte[] address = broadcast.getAddress();
        int hostBits = 32 - prefixLength;
        int numHosts = (int) Math.pow(2, hostBits) - 2;
        
        ExecutorService scanner = Executors.newFixedThreadPool(50);
        
        for (int i = 1; i < numHosts && isDiscovering; i++) {
            final int host = i;
            scanner.submit(() -> {
                try {
                    byte[] targetAddress = address.clone();
                    
                    for (int j = 0; j < 4; j++) {
                        targetAddress[3 - j] = (byte) ((host >> (j * 8)) & 0xFF);
                    }
                    
                    InetAddress target = InetAddress.getByAddress(targetAddress);
                    
                    if (target.isReachable(DISCOVERY_TIMEOUT)) {
                        checkForVNCService(target);
                    }
                } catch (IOException e) {
                    // Ignore unreachable hosts
                }
            });
        }
        
        scanner.shutdown();
        try {
            scanner.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void checkForVNCService(InetAddress address) {
        try {
            for (int port = 5900; port <= 5910; port++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, port), 2000);
                    
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    
                    byte[] buffer = new byte[12];
                    int bytesRead = input.read(buffer);
                    
                    if (bytesRead > 0) {
                        String response = new String(buffer, 0, bytesRead);
                        
                        if (response.contains("RFB") || response.contains("VNC")) {
                            DiscoveredDevice device = createDeviceInfo(address, port);
                            discoveredDevices.add(device);
                            
                            SwingUtilities.invokeLater(() -> {
                                for (DeviceDiscoveryListener listener : listeners) {
                                    listener.onDeviceDiscovered(device);
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    // Port not accessible, continue scanning
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking VNC service on " + address + ": " + e.getMessage());
        }
    }
    
    private void performMDNSDiscovery() {
        // mDNS discovery is optional and requires external library
        // For now, we'll skip it to avoid compilation errors
        System.out.println("mDNS discovery skipped (optional feature)");
    }
    
    private void performSSDPScan() {
        try {
            String ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
                               "HOST: 239.255.255.250:1900\r\n" +
                               "MAN: \"ssdp:discover\"\r\n" +
                               "MX: 3\r\n" +
                               "ST: upnp:rootdevice\r\n\r\n";
            
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(DISCOVERY_TIMEOUT);
            
            InetAddress group = InetAddress.getByName("239.255.255.250");
            DatagramPacket requestPacket = new DatagramPacket(
                ssdpRequest.getBytes(), ssdpRequest.length(), group, 1900);
            
            socket.send(requestPacket);
            
            byte[] buffer = new byte[1024];
            while (isDiscovering) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    
                    if (response.contains("VNC") || response.contains("RFB")) {
                        InetAddress sender = responsePacket.getAddress();
                        checkForVNCService(sender);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            
            socket.close();
        } catch (IOException e) {
            System.err.println("SSDP scan error: " + e.getMessage());
        }
    }
    
    private DiscoveredDevice createDeviceInfo(InetAddress address, int port) {
        String hostname = address.getHostName();
        String deviceType = detectDeviceType(address);
        String os = detectOperatingSystem(address);
        
        return new DiscoveredDevice(address.getHostAddress(), hostname, port, deviceType, os);
    }
    
    private String detectDeviceType(InetAddress address) {
        String host = address.getHostName();
        
        if (host.contains("phone") || host.contains("mobile")) return "Mobile";
        if (host.contains("tablet") || host.contains("ipad")) return "Tablet";
        if (host.contains("laptop")) return "Laptop";
        if (host.contains("desktop") || host.contains("pc")) return "Desktop";
        if (host.contains("server")) return "Server";
        
        return "Unknown";
    }
    
    private String detectOperatingSystem(InetAddress address) {
        try {
            if (address.getHostName().toLowerCase().contains("windows")) return "Windows";
            if (address.getHostName().toLowerCase().contains("linux")) return "Linux";
            if (address.getHostName().toLowerCase().contains("mac")) return "macOS";
            if (address.getHostName().toLowerCase().contains("android")) return "Android";
            if (address.getHostName().toLowerCase().contains("ios")) return "iOS";
        } catch (Exception e) {
            // Fall through to default
        }
        
        return "Unknown";
    }
    
    public Set<DiscoveredDevice> getDiscoveredDevices() {
        return new HashSet<>(discoveredDevices);
    }
    
    public boolean isDiscovering() {
        return isDiscovering;
    }
}
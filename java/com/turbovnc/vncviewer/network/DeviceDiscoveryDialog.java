package com.turbovnc.vncviewer.network;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;

public class DeviceDiscoveryDialog extends JDialog implements DeviceDiscoveryService.DeviceDiscoveryListener {
    private final DeviceDiscoveryService discoveryService;
    private final DefaultTableModel deviceTableModel;
    private final JTable deviceTable;
    private final JButton discoverButton;
    private final JButton connectButton;
    private final JButton refreshButton;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private final JTextArea logArea;
    
    private static final String[] COLUMN_NAMES = {
        "Device Name", "IP Address", "Port", "Type", "OS", "Status"
    };
    
    public DeviceDiscoveryDialog(Frame parent) {
        super(parent != null ? parent : new JFrame(), "SkyNet Device Discovery", true);
        
        this.discoveryService = new DeviceDiscoveryService();
        this.discoveryService.addDiscoveryListener(this);
        
        setLayout(new BorderLayout());
        setSize(800, 600);
        setLocationRelativeTo(parent);
        
        // Create device table
        deviceTableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        deviceTable = new JTable(deviceTableModel);
        deviceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        JScrollPane tableScrollPane = new JScrollPane(deviceTable);
        
        // Create control panel
        JPanel controlPanel = new JPanel(new BorderLayout());
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        discoverButton = new JButton("Start Discovery");
        discoverButton.addActionListener(e -> toggleDiscovery());
        
        refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshDevices());
        refreshButton.setEnabled(false);
        
        connectButton = new JButton("Connect to Device");
        connectButton.addActionListener(e -> connectToSelectedDevice());
        connectButton.setEnabled(false);
        
        deviceTable.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSelection = deviceTable.getSelectedRow() != -1;
            connectButton.setEnabled(hasSelection);
        });
        
        buttonPanel.add(discoverButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(connectButton);
        
        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Ready to discover devices");
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        
        controlPanel.add(buttonPanel, BorderLayout.NORTH);
        controlPanel.add(statusPanel, BorderLayout.SOUTH);
        
        // Log panel
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Discovery Log"));
        
        logArea = new JTextArea(5, 0);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        // Add components to dialog
        add(controlPanel, BorderLayout.NORTH);
        add(tableScrollPane, BorderLayout.CENTER);
        add(logPanel, BorderLayout.SOUTH);
        
        // Add window listener
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (discoveryService.isDiscovering()) {
                    discoveryService.stopDiscovery();
                }
            }
        });
    }
    
    private void toggleDiscovery() {
        if (discoveryService.isDiscovering()) {
            discoveryService.stopDiscovery();
            discoverButton.setText("Start Discovery");
            refreshButton.setEnabled(true);
            progressBar.setVisible(false);
            statusLabel.setText("Discovery stopped");
        } else {
            deviceTableModel.setRowCount(0);
            discoveryService.startDiscovery();
            discoverButton.setText("Stop Discovery");
            refreshButton.setEnabled(false);
            progressBar.setIndeterminate(true);
            progressBar.setVisible(true);
            statusLabel.setText("Discovering devices...");
            log("Discovery started...");
        }
    }
    
    private void refreshDevices() {
        deviceTableModel.setRowCount(0);
        Set<DeviceDiscoveryService.DiscoveredDevice> devices = discoveryService.getDiscoveredDevices();
        
        for (DeviceDiscoveryService.DiscoveredDevice device : devices) {
            addDeviceToTable(device);
        }
        
        statusLabel.setText("Refreshed: " + devices.size() + " devices found");
        log("Refreshed device list: " + devices.size() + " devices");
    }
    
    private void connectToSelectedDevice() {
        int selectedRow = deviceTable.getSelectedRow();
        if (selectedRow == -1) return;
        
        String ipAddress = (String) deviceTableModel.getValueAt(selectedRow, 1);
        String portStr = (String) deviceTableModel.getValueAt(selectedRow, 2);
        String deviceName = (String) deviceTableModel.getValueAt(selectedRow, 0);
        
        try {
            int port = Integer.parseInt(portStr);
            
            // Create connection parameters
            String connectionString = ipAddress + ":" + port;
            
            log("Connecting to " + deviceName + " at " + connectionString);
            
            // This would trigger the main VNC connection
            // For now, we'll show a message
            JOptionPane.showMessageDialog(this, 
                "Connection to " + deviceName + " would be established here.\n" +
                "IP: " + ipAddress + "\nPort: " + port,
                "Connection Initiated", 
                JOptionPane.INFORMATION_MESSAGE);
                
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, 
                "Invalid port number: " + portStr,
                "Connection Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void addDeviceToTable(DeviceDiscoveryService.DiscoveredDevice device) {
        SwingUtilities.invokeLater(() -> {
            Object[] row = {
                device.getHostname(),
                device.getIpAddress(),
                String.valueOf(device.getVncPort()),
                device.getDeviceType(),
                device.getOperatingSystem(),
                "Available"
            };
            deviceTableModel.addRow(row);
        });
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    @Override
    public void onDeviceDiscovered(DeviceDiscoveryService.DiscoveredDevice device) {
        addDeviceToTable(device);
        log("Discovered: " + device.toString());
        
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Found " + deviceTableModel.getRowCount() + " devices");
        });
    }
    
    @Override
    public void onDeviceLost(DeviceDiscoveryService.DiscoveredDevice device) {
        log("Device lost: " + device.toString());
    }
    
    @Override
    public void onDiscoveryStarted() {
        log("Device discovery started...");
    }
    
    @Override
    public void onDiscoveryCompleted() {
        SwingUtilities.invokeLater(() -> {
            discoverButton.setText("Start Discovery");
            refreshButton.setEnabled(true);
            progressBar.setVisible(false);
            statusLabel.setText("Discovery completed: " + deviceTableModel.getRowCount() + " devices found");
            log("Discovery completed. Total devices found: " + deviceTableModel.getRowCount());
        });
    }
    
    public static void showDialog(Frame parent) {
        DeviceDiscoveryDialog dialog = new DeviceDiscoveryDialog(parent);
        dialog.setVisible(true);
    }
    
    public static void showDialog(JDialog parent) {
        DeviceDiscoveryDialog dialog = new DeviceDiscoveryDialog((Frame)null);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
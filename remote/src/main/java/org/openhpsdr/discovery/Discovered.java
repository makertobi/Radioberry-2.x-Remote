package org.openhpsdr.discovery;

import java.io.Serializable;

public class Discovered implements Serializable {

    private static final long serialVersionUID = 1L;

    public Discovered() {}

    public void setInterface(String iface) {
        this.iface=iface;
    }

    public void setInterfaceName(String name) {
        this.ifacename=name;
    }

    public void setDevice(int device) {
        this.device = device;
    }

    public void setSoftwareversion(int softwareversion) {
        this.softwareversion = softwareversion;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public void setState(int state) {
        this.state = state;
    }

    public String getInterface() {
        return iface;
    }

    public String getInterfaceName() {
        return ifacename;
    }

    public int getDevice() {
        return device;
    }

    public String getDeviceName() {
        String result = "Unknown";
        switch (device) {
            case DEVICE_HERMES_LITE:
                result = "Hermes Lite";
                break;
        }
        return result;
    }

    public int getSoftwareversion() {
        return softwareversion;
    }

    public String getAddress() {
        return address;
    }

    public String getMac() {
        return mac;
    }

    public int getState() {
        return state;
    }

    public String getStateName() {
        String result = "";
        switch (state) {
            case 2:
                result = "available";
                break;
            case 3:
                result = "in use";
                break;
        }
        return result;
    }

    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(getDeviceName());
        result.append(" Interface: "+ifacename+" ("+iface+")");
        result.append(" Address:");
        result.append(address);
        result.append(" (");
        result.append(mac);
        result.append(") (software version:");
        result.append(String.format("%02X", softwareversion));
        result.append(") state:");
        result.append(getStateName());

        return result.toString();
    }

    public static final int DEVICE_HERMES_LITE = 6;

    private int device;
    private int softwareversion;
    private String address;
    private String mac;
    private int state;
    private String iface;
    private String ifacename;
}

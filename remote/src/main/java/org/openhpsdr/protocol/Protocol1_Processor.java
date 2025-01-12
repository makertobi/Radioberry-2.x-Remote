package org.openhpsdr.protocol;

import org.openhpsdr.discovery.Discovered;
import org.radioberry.domain.Radio;
import org.radioberry.radio.IStreamRxIQ;
import org.radioberry.utility.Configuration;
import org.radioberry.utility.Log;

import javax.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.*;

@ApplicationScoped
public class Protocol1_Processor implements Runnable {

  private IStreamRxIQ streamRxIQ;

  private Discovered useDiscovered;

  private volatile Thread thread;

  private long frequency = 3630000;
  private int rf_gain = 19;

  private final int toport = 1024;
  private final int myport = 0;

  private DatagramSocket socket;

  private byte rxbuffer[] = new byte[1032];
  private DatagramPacket rxdatagram;
  private long ep6sequence = -1;

  private boolean running;

  private byte SYNC = 0x7F;

  private byte rxcontrol0;
  private byte rxcontrol1;
  private byte rxcontrol2;
  private byte rxcontrol3;
  private byte rxcontrol4;

  private int txoffset = 16;
  private byte[] sendbuffer = new byte[1032];
  private byte[] commandbuffer = new byte[64];

  private InetAddress toaddress;
  private DatagramPacket commanddatagram;
  private DatagramPacket samplesdatagram;

  private static final int STATE_SYNC0 = 0;
  private static final int STATE_SYNC1 = 1;
  private static final int STATE_SYNC2 = 2;
  private static final int STATE_CONTROL0 = 3;
  private static final int STATE_CONTROL1 = 4;
  private static final int STATE_CONTROL2 = 5;
  private static final int STATE_CONTROL3 = 6;
  private static final int STATE_CONTROL4 = 7;
  private static final int STATE_I0 = 8;
  private static final int STATE_I1 = 9;
  private static final int STATE_I2 = 10;
  private static final int STATE_Q0 = 11;
  private static final int STATE_Q1 = 12;
  private static final int STATE_Q2 = 13;
  private static final int STATE_M0 = 14;
  private static final int STATE_M1 = 15;
  private static final int STATE_SYNC_ERROR = 16;

  private int state = STATE_SYNC0;

  private int command = 0;
  private int freqcommand = 0;

  private boolean transmit = false;
  private boolean tuning = false;

  private int isample;
  private int qsample;

  private byte[] audiooutput = new byte[1024 * 4]; // 2 channels of shorts
  private int audiooutputindex = 0;

  public static byte MOX_DISABLED = (byte) 0x00;
  public static byte MOX_ENABLED = (byte) 0x01;

  private byte txcontrol0 = (byte) (MOX_DISABLED);

  private long txsequence = 0L;

  public void setStreamRxIQHandler(IStreamRxIQ streamRxIQ) {
    this.streamRxIQ = streamRxIQ;
  }

  public void start(Discovered useThisDiscovered) {
    useDiscovered = useThisDiscovered;
    if (thread == null) {
      thread = new Thread(this);
      thread.setPriority(Thread.MAX_PRIORITY);
      thread.start();
    }
  }

  public void setRadioSettings(Radio radio) {
    this.frequency = (long) radio.getFrequency();
    this.rf_gain = radio.getRf_gain();
    this.transmit = radio.isTransmit();
  }

  public boolean isTransmitting() {
    return this.transmit;
  }

  public void terminate() {
    running = false;
  }

  public boolean isRunning() {
    return running;
  }

  @Override
  public void run() {

    int status;
    long sequence;
    int endpoint;

    try {
      toaddress = InetAddress.getByName(useDiscovered.getAddress());
    } catch (Exception e) {
      Log.info("Protocol1", "constructor: " + e.toString());
    }

    commanddatagram = new DatagramPacket(commandbuffer, commandbuffer.length, toaddress, toport);
    samplesdatagram = new DatagramPacket(sendbuffer, sendbuffer.length, toaddress, toport);

    running = true;

    try {
      InetSocketAddress socketaddress = new InetSocketAddress(useDiscovered.getInterface(), myport);

      socket = new DatagramSocket(null);
      socket.setReuseAddress(true);
      socket.setBroadcast(true);
      socket.setSoTimeout(0);
      socket.bind(socketaddress);
      InetAddress address = InetAddress.getByName(useDiscovered.getAddress());
      rxdatagram = new DatagramPacket(rxbuffer, rxbuffer.length, address, toport);

      sendStartRadio();

      while (running) {
        socket.receive(rxdatagram);
        if (rxdatagram.getLength() == 1032 && (rxbuffer[0] & 0xFF) == 0xEF && (rxbuffer[1] & 0xFF) == 0xFE) {
          status = rxbuffer[2] & 0xFF;
          if (status == 1) {
            endpoint = rxbuffer[3] & 0xFF;
            if (endpoint == 6) {
              sequence = ((rxbuffer[4] & 0xFF) << 24)
                | ((rxbuffer[5] & 0xFF) << 16)
                | ((rxbuffer[6] & 0xFF) << 8)
                | ((rxbuffer[7] & 0xFF));
              ep6sequence++;
              if (sequence != ep6sequence) {
                ep6sequence = sequence;
              }
              demuxBuffer(rxbuffer, 8);
              demuxBuffer(rxbuffer, 520);
            }
          } else {
            Log.info("Protocol1", "run: received unknown status: " + status);
          }
        } else {
          Log.info("Protocol1", "run: received unknown packet: length:" + rxdatagram.getLength());
        }
      }
    } catch (SocketException se) {
      Log.info("Protocol1", "run: " + se.toString());
    } catch (UnknownHostException uhe) {
      Log.info("Protocol1", "run: " + uhe.toString());
    } catch (IOException ioe) {
      Log.info("Protocol1", "run: " + ioe.toString());
    }

    // send stop command
    commandbuffer[0] = (byte) 0xEF;
    commandbuffer[1] = (byte) 0xFE;
    commandbuffer[2] = (byte) 0x04;
    commandbuffer[3] = (byte) 0x00;
    for (int i = 4; i < 64; i++) {
      commandbuffer[i] = (byte) 0x00;
    }
    sendCommand();
    socket.close();

    System.out.println("Protocol1 Processor is stopped; only when stopping the service.");
  }

  private synchronized void sendStartRadio() {
    commandbuffer[0] = (byte) 0xEF;
    commandbuffer[1] = (byte) 0xFE;
    commandbuffer[2] = (byte) 0x04;
    commandbuffer[3] = (byte) 0x01;
    for (int i = 4; i < 64; i++) {
      commandbuffer[i] = (byte) 0x00;
    }
    sendCommand();
  }

  private void demuxBuffer(byte[] bytes, int offset) {

    if (running) {
      state = STATE_SYNC0;
      for (int i = offset; i < offset + 512; i++) {
        switch (state) {
          case STATE_SYNC0:
            if (bytes[i] != SYNC) {
              Log.info("Protocol1", "SYNC error: offset:" + offset + ":" + String.format("%02X", bytes[i]));
              state = STATE_SYNC_ERROR;
            } else {
              state++;
            }
            break;
          case STATE_SYNC1:
            if (bytes[i] != SYNC) {
              Log.info("Protocol1", "SYNC error: offset:" + offset + ":" + String.format("%02X", bytes[i]));
              state = STATE_SYNC_ERROR;
            } else {
              state++;
            }
            break;
          case STATE_SYNC2:
            if (bytes[i] != SYNC) {
              Log.info("Protocol1", "SYNC error: offset:" + offset + ":" + String.format("%02X", bytes[i]));
              state = STATE_SYNC_ERROR;
            } else {
              state++;
            }
            break;
          case STATE_CONTROL0:
            rxcontrol0 = (byte) (bytes[i] & 0xFF);
            state++;
            break;
          case STATE_CONTROL1:
            rxcontrol1 = (byte) (bytes[i] & 0xFF);
            state++;
            break;
          case STATE_CONTROL2:
            rxcontrol2 = (byte) (bytes[i] & 0xFF);
            state++;
            break;
          case STATE_CONTROL3:
            rxcontrol3 = (byte) (bytes[i] & 0xFF);
            state++;
            break;
          case STATE_CONTROL4:
            rxcontrol4 = (byte) (bytes[i] & 0xFF);
            state++;
            break;
          case STATE_I0:
            isample = bytes[i] << 16; // keep sign
            state++;
            break;
          case STATE_I1:
            isample |= (bytes[i] & 0xFF) << 8;
            state++;
            break;
          case STATE_I2:
            isample |= bytes[i] & 0xFF;
            state++;
            break;
          case STATE_Q0:
            qsample = bytes[i] << 16; // keep sign
            state++;
            break;
          case STATE_Q1:
            qsample |= (bytes[i] & 0xFF) << 8;
            state++;
            break;
          case STATE_Q2:
            qsample |= bytes[i] & 0xFF;
            state++;
            break;
          case STATE_M0:
            state++;
            break;
          case STATE_M1:
            this.streamRxIQ.processStreamRxIQ((float) isample / 8388607.0F, (float) qsample / 8388607.0F );
            state = STATE_I0;
            break;
          case STATE_SYNC_ERROR:
            return;
        }
      }
    }
  }

  public synchronized void sendCommand() {
    try {
      socket.send(commanddatagram);
    } catch (SocketException se) {
      Log.info("Protocol1", "sendCommand: " + se.toString());
    } catch (UnknownHostException uhe) {
      Log.info("Protocol1", "sendCommand: " + uhe.toString());
    } catch (IOException ioe) {
      Log.info("Protocol1", "sendCommand: " + ioe.toString());
    }
  }

  public synchronized void sendSamples(float[] outlsamples, float[] outrsamples) {
    float rfgain = 1.0F;

    if (!running) return;

    for (int j = 0; j < outlsamples.length; j++) {

      if (transmit) {
        sendbuffer[txoffset++] = (byte) 0; // rx
        sendbuffer[txoffset++] = (byte) 0; // rx
        sendbuffer[txoffset++] = (byte) 0; // rx
        sendbuffer[txoffset++] = (byte) 0; // rx
        short l = (short) (outlsamples[j] * 32767.0F * rfgain);
        short r = (short) (outrsamples[j] * 32767.0F * rfgain);
        sendbuffer[txoffset++] = (byte) ((l >> 8) & 0xFF);
        sendbuffer[txoffset++] = (byte) (l & 0xFF);
        sendbuffer[txoffset++] = (byte) ((r >> 8) & 0xFF);
        sendbuffer[txoffset++] = (byte) (r & 0xFF);
      } else {
        short l = (short) (outlsamples[j] * 32767.0F * Configuration.afgain);
        short r = (short) (outrsamples[j] * 32767.0F * Configuration.afgain);
        sendbuffer[txoffset++] = (byte) ((l >> 8) & 0xFF);
        sendbuffer[txoffset++] = (byte) (l & 0xFF);
        sendbuffer[txoffset++] = (byte) ((r >> 8) & 0xFF);
        sendbuffer[txoffset++] = (byte) (r & 0xFF);
        sendbuffer[txoffset++] = (byte) 0; // tx
        sendbuffer[txoffset++] = (byte) 0; // tx
        sendbuffer[txoffset++] = (byte) 0; // tx
        sendbuffer[txoffset++] = (byte) 0; // tx
      }

      if (txoffset == 520) {
        txoffset = 528;
      } else if (txoffset == 1032) {
        sendbuffer[0] = (byte) 0xEF;
        sendbuffer[1] = (byte) 0xFE;
        sendbuffer[2] = (byte) 0x01;
        sendbuffer[3] = (byte) 0x02;
        sendbuffer[4] = (byte) ((txsequence >> 24) & 0xFF);
        sendbuffer[5] = (byte) ((txsequence >> 16) & 0xFF);
        sendbuffer[6] = (byte) ((txsequence >> 8) & 0xFF);
        sendbuffer[7] = (byte) (txsequence & 0xFF);
        sendbuffer[8] = SYNC;
        sendbuffer[9] = SYNC;
        sendbuffer[10] = SYNC;

        byte mox = transmit ? MOX_ENABLED : MOX_DISABLED;

        switch (command) {
          case 0: {
            sendbuffer[11] = txcontrol0;
            sendbuffer[12] = (byte) 0x00;
            sendbuffer[13] = getFilter();
            sendbuffer[14] = 0x00;
            sendbuffer[15] = 0x00;
            command++;
            break;
          }
          case 1: {
            sendbuffer[11] = 0x12; //ADDR 0xO9
            sendbuffer[12] = 0x00; //C1
            byte drive = (byte) 255;
            sendbuffer[12] = drive;
            byte c2 = 0x08; //pa enable
            sendbuffer[13] = c2;
            sendbuffer[14] = (byte) 0;
            sendbuffer[15] = (byte) 0;
            command++;
            break;
          }
          case 2: {
            sendbuffer[11] = 0x14; //ADDR 0xOA
            sendbuffer[12] = 0x00;
            sendbuffer[13] = 0x00;
            sendbuffer[14] = 0x00;
            sendbuffer[15] = (byte) (0x40 | (this.rf_gain & 0x7F));
            command++;
            break;
          }
          case 3: {
            sendbuffer[11] = 0x16; //ADDR 0xOB
            sendbuffer[12] = 0x00;
            sendbuffer[13] = 0x00;
            sendbuffer[14] = 0x00;
            sendbuffer[15] = 0x00;
            command++;
            break;
          }
          case 4: {
            sendbuffer[11] = 0x1C; //ADDR 0xOE
            sendbuffer[12] = 0x00;
            sendbuffer[13] = 0x00;
            sendbuffer[14] = 0x00;
            sendbuffer[15] = 0x00;
            command++;
            break;
          }
          case 5: {
            sendbuffer[11] = 0x2E; //ADDR 0x17
            sendbuffer[12] = 0x00;
            sendbuffer[13] = 0x00;
            sendbuffer[14] = (byte) 0x1F;
            sendbuffer[15] = (byte) 0x80;
            command++;
            break;
          }
          case 6: {
            sendbuffer[11] = 0x20;
            sendbuffer[12] = 0x00;
            sendbuffer[13] = 0x00;
            sendbuffer[14] = 0x00;
            sendbuffer[15] = 0x00;
            command = 0;
            break;
          }
        }
        // turn on transmit if needed
        sendbuffer[11] |= mox;

        sendbuffer[520] = SYNC;
        sendbuffer[521] = SYNC;
        sendbuffer[522] = SYNC;

        switch (freqcommand) {
          case 0: { // rx frequency
            sendbuffer[523] = (byte) (txcontrol0 | 0x04 | mox);
            sendbuffer[524] = (byte) ((frequency >> 24) & 0xFF);
            sendbuffer[525] = (byte) ((frequency >> 16) & 0xFF);
            sendbuffer[526] = (byte) ((frequency >> 8) & 0xFF);
            sendbuffer[527] = (byte) (frequency & 0xFF);
            freqcommand++;
            break;
          }
          case 1: { // tx frequency
            sendbuffer[523] = (byte) (txcontrol0 | 0x02 | mox);
            sendbuffer[524] = (byte) ((frequency >> 24) & 0xFF);
            sendbuffer[525] = (byte) ((frequency >> 16) & 0xFF);
            sendbuffer[526] = (byte) ((frequency >> 8) & 0xFF);
            sendbuffer[527] = (byte) (frequency & 0xFF);
            freqcommand = 0;
            break;
          }
        }
        sendStreamToRadio();
        txoffset = 16;
      }
    }
  }

  private byte getFilter() {
    byte filter = (byte) 2; /* 160 meters */

    if (frequency > 22000000) filter = (byte)192; /* 12/10 meters */
    else if (frequency > 15000000) filter = (byte) 160; /* 17/15 meters */
    else if (frequency > 8000000) filter = (byte) 144; /* 30/20 meters */
    else if (frequency > 4500000) filter = (byte) 136; /* 60/40 meters */
    else if (frequency > 2400000) filter = (byte) 132; /* 80 meters */

    return filter;
  }

  public synchronized void sendStreamToRadio() {
    try {
      socket.send(samplesdatagram);
    } catch (SocketException se) {
      Log.info("Protocol1", "send: " + se.toString());
    } catch (UnknownHostException uhe) {
      Log.info("Protocol1", "send: " + uhe.toString());
    } catch (IOException ioe) {
      Log.info("Protocol1", "send: " + ioe.toString());
    }
    txsequence++;
  }
}

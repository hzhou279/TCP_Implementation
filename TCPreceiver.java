import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

public class TCPreceiver {

  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected int MTU; // maximum transmission unit in bytes
  protected int SWS; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  protected long startTime = System.nanoTime();

  protected DatagramSocket socket;
  protected FileOutputStream fos;
  protected File outFile;

  protected LinkedBlockingQueue<TCPsegment> slidingWindow;
  protected Integer ackedSeqNum;
  // protected int writtenSeqNum;
  protected int sequenceNum;
  protected int curSeqNum;
  protected int curSegIdx;
  protected Integer curAckedSegIdx;

  protected ArrayList<TCPsegment> rcvBuf;

  protected Timer timer;
  protected TimerTask retransmit;
  protected long TO = 3000;

  protected int unlockCnt;

  protected Integer dataReceivedCnt;
  protected Integer packetsReceivedCnt;
  protected Integer outOfSeqPacketsDiscardedCnt;
  protected Integer inChecksumPackectsDiscardedCnt;
  protected Integer dupAckCnt;

  class segmentComparator implements Comparator<TCPsegment> {
    @Override
    public int compare(TCPsegment o1, TCPsegment o2) {
      if (o1.getSequenceNum() > o2.getSequenceNum())
        return 1;
      else if (o1.getSequenceNum() == o2.getSequenceNum())
        return 0;
      else
        return -1;
    }
  }

  class Producer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;

    public Producer(LinkedBlockingQueue<TCPsegment> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      try {
        this.produce();
      } catch (IOException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    public void produce() throws IOException, InterruptedException {
      while (true) {
        // client receives data package from server
        byte[] tcpBuf = new byte[MTU + TCPsegment.headerLength];
        DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length);
        try {
        socket.setSoTimeout(10000);
        socket.receive(dataPacket);
        } catch (SocketTimeoutException e) {
          System.out.println("No package received from sender for 10 secs.");
          // e.printStackTrace();
          printStats();
          System.exit(0);
        }
        TCPsegment dataTCP = new TCPsegment();
        dataTCP = dataTCP.deserialize(dataPacket.getData(), 0, tcpBuf.length); // dataBuf.length mismatch
        // output data tcp received
        dataTCP.setTime(System.nanoTime() - startTime);
        dataTCP.printInfo(false);

        // check if client receives first FIN from server
        if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
          this.queue.add(dataTCP);
          break;
        }

        // client drops dataTCP out of current sliding window
        synchronized (ackedSeqNum) {
          if (dataTCP.getSequenceNum() < ackedSeqNum || dataTCP.getSequenceNum() > ackedSeqNum + SWS * MTU) {
            // System.out.println("seqNum: " + dataTCP.getSequenceNum() + " ackedSeqNum: " +
            // ackedSeqNum);
            // System.out.println("Sliding window from: " + ackedSeqNum + " to: " +
            // (ackedSeqNum + (SWS + 1) * MTU));
            outOfSeqPacketsDiscardedCnt++;
            unlockCnt++;
            if (unlockCnt <= 3)
              continue;
            else
              unlockCnt = 0;
          }
        }

        if (dataTCP.getData() == null)
          continue;

        // check if data packet is valid
        if (dataTCP.getData() != null && dataTCP.getLength() != dataTCP.getData().length) {
          System.out.println("drop once");
          continue;
        }

        short oldChecksum = dataTCP.getChecksum();
        dataTCP.setChecksum((short)0);
        dataTCP.serialize();
        if (oldChecksum != dataTCP.getChecksum()) {
          inChecksumPackectsDiscardedCnt++;
          System.out.println("checksum failed");
          continue;
        }
          
        // if (oldChecksum != dataTCP.deserialize(dataTCP.serialize(), 0, dataTCP.length).getChecksum()) {
        //   // drop packet
        //   System.out.println("drop once");
        //   continue;
        // }

        packetsReceivedCnt++;
        dataReceivedCnt += dataTCP.getLength();
        this.queue.add(dataTCP);
      }
    }
  }

  // Consumer is responsible for writing data to file and update ackedSeqNum
  class Consumer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;

    public Consumer(LinkedBlockingQueue<TCPsegment> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      try {
        synchronized (queue) {
          this.consume();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void consume() throws IOException {
      // ackedSeqNum = 1;
      while (true) {
        TCPsegment dataTCP = null;
        try {
          dataTCP = this.queue.take();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (dataTCP == null)
          System.out.println("Consumer get no TCP segments.");

        long timestamp = dataTCP.getTimestamp();
        synchronized (ackedSeqNum) {
          if (dataTCP.getSequenceNum() == ackedSeqNum && dataTCP.getLength() != 0) {
            if (dataTCP.getData() == null)
              System.out.println("dataTCP.getData() is null");
            byte[] dataBuf = dataTCP.getData();
            fos.write(dataBuf);
            // update acknowledgement number
            ackedSeqNum += dataTCP.getLength();

            rcvBuf.sort(new segmentComparator());
            // final int size = rcvBuf.size();
            for (int i = 0; i < rcvBuf.size(); i++) {
              if (rcvBuf.get(i).getSequenceNum() == ackedSeqNum) {
                fos.write(rcvBuf.get(i).getData());
                ackedSeqNum += rcvBuf.get(i).getLength();
                timestamp = rcvBuf.get(i).getTimestamp();
                rcvBuf.remove(i);
                i--;
              } else if (dataTCP.getSequenceNum() > ackedSeqNum + SWS * MTU || dataTCP.getSequenceNum() < ackedSeqNum) {
                rcvBuf.remove(i);
                i--;
              }
            }
          } else if (dataTCP.getSequenceNum() > ackedSeqNum) {
            rcvBuf.add(dataTCP);
          }

          // check if client receives first FIN from server
          if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
            // clients sends out second FIN + ACK to server
            TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), 1,
                dataTCP.getSequenceNum() + 1, dataTCP.getTimestamp());
            byte[] secondFINACKBuf = secondFINACKTCP.serialize();
            DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, secondFINACKBuf.length, remoteIP,
                remotePort);
            socket.send(secondFINACKPacket);
            // output second FIN + ACK TCP segment sent
            secondFINACKTCP.setTime(System.nanoTime() - startTime);
            secondFINACKTCP.printInfo(true);
            break;
          }

          // clients sends out acknowledgement to server
          TCPsegment ackTCP = new TCPsegment(TCPsegment.ACK, 1, ackedSeqNum, timestamp);
          byte[] ackBuf = ackTCP.serialize();
          DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length, remoteIP, remotePort);
          socket.send(ackPacket);
          // output acknowledgement TCP segment sent
          ackTCP.setTime(System.nanoTime() - startTime);
          ackTCP.printInfo(true);
        }
      }
    }
  }

  public TCPreceiver(int port, String fileName, int MTU, int SWS) {
    this.port = port;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24;
    this.SWS = SWS;
    slidingWindow = new LinkedBlockingQueue<TCPsegment>(this.SWS);

    this.dataReceivedCnt = 0;
    this.packetsReceivedCnt = 0;
    this.outOfSeqPacketsDiscardedCnt = 0;
    this.inChecksumPackectsDiscardedCnt = 0;

    timer = new Timer();
    try {
      printInfo();

      socket = new DatagramSocket(port);

      // client receives initial SYN from server
      while (true) {
        byte[] buf = new byte[TCPsegment.headerLength + this.MTU];
        DatagramPacket initialPacket = new DatagramPacket(buf, TCPsegment.headerLength);
        // System.out.println("Wait fot server SYN....");
        socket.receive(initialPacket);
        TCPsegment initialTCP = new TCPsegment();
        initialTCP = initialTCP.deserialize(initialPacket.getData(), 0, buf.length);
        if (initialTCP.getFlag() == TCPsegment.ACK && initialTCP.getLength() != 0 && initialTCP.getData() != null) {
          this.slidingWindow.add(initialTCP);
          this.remoteIP = initialPacket.getAddress();
          this.remotePort = initialPacket.getPort();
          break;
        }

        short oldChecksum = initialTCP.getChecksum();
        initialTCP.setChecksum((short)0);
        initialTCP.serialize();
        if (oldChecksum != initialTCP.getChecksum()) {
          System.out.println("SYN from server checksum failed.");
          inChecksumPackectsDiscardedCnt++;
          continue;
        }

        // check initial sequence number
        if (initialTCP.getSequenceNum() != 0 || initialTCP.getAcknowledgement() != 0)
          continue;

        // output first SYN received
        initialTCP.setTime(System.nanoTime() - this.startTime);
        initialTCP.printInfo(false);
        if (initialTCP.getFlag() == TCPsegment.SYN) {
          // client sends subsequent SYN + ACK back to server
          // int acknowledgement = initialTCP.getSequenceNum() + 1;
          long acknowledgedTimestamp = initialTCP.getTimestamp();
          TCPsegment secondTCP = new TCPsegment((byte) (TCPsegment.SYN + TCPsegment.ACK), 0,
              initialTCP.getSequenceNum() + 1, acknowledgedTimestamp);
          byte[] secondBuf = secondTCP.serialize();
          InetAddress serverIP = initialPacket.getAddress();
          int serverPort = initialPacket.getPort();
          DatagramPacket secondPacket = new DatagramPacket(secondBuf, secondBuf.length, serverIP, serverPort);
          socket.send(secondPacket);
          // output second SYN + ACK sent
          secondTCP.setTime(System.nanoTime() - this.startTime);
          secondTCP.printInfo(true);

          // client receives final ACK
          byte[] finalBuf = new byte[TCPsegment.headerLength + this.MTU];
          DatagramPacket finalPacket = new DatagramPacket(finalBuf, TCPsegment.headerLength + this.MTU);
          socket.receive(finalPacket);

          TCPsegment finalTCP = new TCPsegment();
          finalTCP = finalTCP.deserialize(finalPacket.getData(), 0, finalBuf.length);

          oldChecksum = finalTCP.getChecksum();
          finalTCP.setChecksum((short)0);
          finalTCP.serialize();
          if (oldChecksum != finalTCP.getChecksum()) {
            System.out.println("final ACK from server checksum failed.");
            inChecksumPackectsDiscardedCnt++;
            continue;
          }

          // output final ACK received
          if (finalTCP.getSequenceNum() != 1 || finalTCP.getAcknowledgement() != 1)
            continue;
          if (finalTCP.getFlag() == TCPsegment.SYN)
            continue;
          if (finalTCP.getData() == null) {
            finalTCP.setTime(System.nanoTime() - this.startTime);
            finalTCP.printInfo(false);
            this.remoteIP = initialPacket.getAddress();
            this.remotePort = initialPacket.getPort();
            break;
          } else {
            this.slidingWindow.add(finalTCP);
            this.remoteIP = initialPacket.getAddress();
            this.remotePort = initialPacket.getPort();
            break;
          }
          // set server IP and port up
          // this.remoteIP = finalPacket.getAddress();
          // this.remotePort = finalPacket.getPort();

        }
      }

      // retransmit = this.createRetransmitTask(secondTCP, secondPacket);
      // timer.schedule(retransmit, this.TO, this.TO);

      // retransmit.cancel();
      // retransmit = null;
      // timer.purge();

      // initial sequence number of client set to 0
      // if (finalTCP.getAcknowledgement() != 1) {
      // System.out.println("Acknowledgement received: " +
      // finalTCP.getAcknowledgement());
      // System.out.println("ACK from server confirmation fails.");
      // System.exit(1);
      // }

      // System.out.println("Connection established!!!\n-----Begin data
      // transmission-----");

      outFile = new File(this.fileName);
      if (!outFile.createNewFile()) {
        System.out.println("Output file exists.");
        System.exit(1);
      }
      fos = new FileOutputStream(outFile);

      this.ackedSeqNum = 1;
      this.rcvBuf = new ArrayList<TCPsegment>();
      this.unlockCnt = 0;

      // // begin data transmission
      // int acknowledgementNum = 1;
      // while (true) {
      // // client receives data from server
      // byte[] tcpBuf = new byte[this.MTU + TCPsegment.headerLength];
      // DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length);
      // // socket.setSoTimeout(5 * 1000);
      // socket.receive(dataPacket);
      // TCPsegment dataTCP = new TCPsegment();
      // dataTCP = dataTCP.deserialize(dataPacket.getData(), 0, tcpBuf.length); //
      // dataBuf.length mismatch
      // // output data tcp received
      // dataTCP.setTime(System.nanoTime() - this.startTime);
      // dataTCP.printInfo(false);

      // // check if client receives first FIN from server
      // if (dataTCP.getFlag() == TCPsegment.FIN + TCPsegment.ACK) {
      // // clients sends out second FIN + ACK to server
      // TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN +
      // TCPsegment.ACK), 1,
      // dataTCP.getSequenceNum() + 1, dataTCP.getTimestamp());
      // byte[] secondFINACKBuf = secondFINACKTCP.serialize();
      // DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf,
      // secondFINACKBuf.length, remoteIP,
      // remotePort);
      // socket.send(secondFINACKPacket);
      // // output second FIN + ACK TCP segment sent
      // secondFINACKTCP.setTime(System.nanoTime() - this.startTime);
      // secondFINACKTCP.printInfo(true);
      // break;
      // }

      // // write data received into output file
      // // drop duplicate data package
      // if (dataTCP.getSequenceNum() >= acknowledgementNum) {
      // if (dataTCP.getData() == null)
      // System.out.println("dataTCP.getData() is null");
      // byte[] dataBuf = dataTCP.getData();
      // fos.write(dataBuf);

      // // update acknowledgement number
      // acknowledgementNum += dataTCP.getLength();
      // }

      // // clients sends out acknowledgement to server
      // TCPsegment ackTCP = new TCPsegment(TCPsegment.ACK, 1, acknowledgementNum,
      // dataTCP.getTimestamp());
      // byte[] ackBuf = ackTCP.serialize();
      // DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length,
      // remoteIP, remotePort);
      // socket.send(ackPacket);
      // // output acknowledgement TCP segment sent
      // ackTCP.setTime(System.nanoTime() - this.startTime);
      // ackTCP.printInfo(true);
      // }

      Producer p = new Producer(slidingWindow);
      Consumer c = new Consumer(slidingWindow);
      Thread pThread = new Thread(p);
      Thread cThread = new Thread(c);
      pThread.start();
      cThread.start();

      try {
        pThread.join();
        cThread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      // clients sets up second FIN + ACK retransmit timer to server
      TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), 1,
      ackedSeqNum + 1, System.nanoTime());
      byte[] secondFINACKBuf = secondFINACKTCP.serialize();
      DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, secondFINACKBuf.length, remoteIP,
          remotePort);
      retransmit = createRetransmitTask(secondFINACKTCP, secondFINACKPacket);
      timer.schedule(retransmit, 1000, 1000);

      // client receives last ACK from server
      while (true) {
        byte[] lastACKBuf = new byte[TCPsegment.headerLength + this.MTU];
        DatagramPacket lastACKPacket = new DatagramPacket(lastACKBuf, lastACKBuf.length);
        // System.out.println("reach 387");
        socket.receive(lastACKPacket);
        TCPsegment lastACKTCP = new TCPsegment();
        lastACKTCP = lastACKTCP.deserialize(lastACKPacket.getData(), 0, lastACKBuf.length); // dataBuf.length mismatch
        // output last ACK received
        lastACKTCP.setTime(System.nanoTime() - this.startTime);
        lastACKTCP.printInfo(false);
        // System.out.println("reach 394");
        if (lastACKTCP.getFlag() == TCPsegment.ACK && lastACKTCP.getLength() == 0) {
          // System.out.println("reach asdasdasd");
          retransmit.cancel();
          retransmit = null;
          timer.purge();
          break;
        }
        // client receives first FIN + ACK as server does not receive client's ACK
        // else if (lastACKTCP.getFlag() == (byte)TCPsegment.ACK + TCPsegment.FIN) {
        // else {
        //   // clients sends out second FIN + ACK to server
        //   TCPsegment secondFINACKTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), 1,
        //   lastACKTCP.getSequenceNum() + 1, lastACKTCP.getTimestamp());
        //   byte[] secondFINACKBuf = secondFINACKTCP.serialize();
        //   DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, secondFINACKBuf.length, remoteIP,
        //       remotePort);
        //   socket.send(secondFINACKPacket);
        //   // output second FIN + ACK TCP segment sent
        //   secondFINACKTCP.setTime(System.nanoTime() - startTime);
        //   secondFINACKTCP.printInfo(true);
        // }
        // System.out.println("reach 396");
      }

      // System.out.println("reach 415");

      timer.cancel();
      socket.close();
    } catch (SocketException e) {
      System.out.println("Sender socket error.");
      e.printStackTrace();
    } catch (IOException e) {
      System.out.println("An I/O exception occurs.");
      e.printStackTrace();
    } finally {
      if (socket != null)
        socket.close();
      try {
        if (fos != null)
          fos.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void printInfo() {
    System.out
        .println("TCP client created with port: " + port + " fileName: " + fileName + " MTU: " + MTU + " SWS: " + SWS);
  }

  public void printStats() {
    String out = "--------------------------\n";
    out += "- Amount of Data received: " + this.dataReceivedCnt;
    out += "\n- Number of packets received: " + this.packetsReceivedCnt;
    out += "\n- Number of out-of-sequence packets discarded: " + this.outOfSeqPacketsDiscardedCnt;
    out += "\n- Number of packets discarded due to incorrect checksum: " + this.inChecksumPackectsDiscardedCnt;
    out += "\n--------------------------";
    System.out.println(out);
  }

  public TimerTask createRetransmitTask(TCPsegment tcpToRetransmit, DatagramPacket packetToRetransmit) {
    return new TimerTask() {
      @Override
      public void run() {
        if (packetToRetransmit == null || tcpToRetransmit == null) {
          System.out.println("timer cancelled once.");
          return;
        }
        try {
          socket.send(packetToRetransmit);
        } catch (IOException e) {
          e.printStackTrace();
        }
        tcpToRetransmit.setTime(System.nanoTime() - startTime);
        tcpToRetransmit.printInfo(true);
      }
    };
  }
}

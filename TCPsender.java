public class TCPsender {
  
  protected int port;
  protected int remoteIP;
  protected int remotePort;
  protected String fileName;
  protected byte mtu; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver
  
  public TCPsender(int port, int remoteIP, int remotePort, String fileName, byte mtu, int sws) {
    this.port = port;
    this.remoteIP = remoteIP;
    this.remotePort = remotePort;
    this.fileName = fileName;
    this.mtu = mtu;
    this.sws = sws;
  }

  public void printInfo() {
    System.out.println("TCP sender created with port: " + port + " remoteIP: " + remoteIP + 
    " remotePort: " + remotePort + " fileName: " + fileName + " mtu: "+ mtu + " sws: " + sws);
  }
}

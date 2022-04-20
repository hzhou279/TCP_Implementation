public class TCPreceiver {
  
  protected int port;
  protected String fileName;
  protected byte mtu; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  public TCPreceiver(int port, String fileName, byte mtu, int sws) {
    
  }

  public void printInfo() {
    System.out.println("TCP receiver created with port: " + port + " fileName: " + fileName + " mtu: "+ mtu + " sws: " + sws);
  }
}

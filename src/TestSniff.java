import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import se.sics.jipv6.core.HC06Packeter;
import se.sics.jipv6.core.HopByHopOption;
import se.sics.jipv6.core.ICMP6Packet;
import se.sics.jipv6.core.IPPayload;
import se.sics.jipv6.core.IPv6ExtensionHeader;
import se.sics.jipv6.core.IPv6Packet;
import se.sics.jipv6.core.Packet;
import se.sics.jipv6.core.UDPPacket;
import se.sics.jipv6.mac.IEEE802154Handler;
import se.sics.jipv6.util.Utils;

public class TestSniff {
    /* Run JIPv6 over TUN on linux of OS-X */

    /* MAC packet received */
    public void analyzePacket(Packet packet) {
        
    }
    
    /* IPv6 packet received */
    public void analyzeIPPacket(IPv6Packet packet) {
        IPPayload payload = packet.getIPPayload();
        while (payload instanceof IPv6ExtensionHeader) {
            System.out.println("Analyzer - EXT HDR:");
            payload.printPacket(System.out);
            payload = ((IPv6ExtensionHeader) payload).getNext();
        }
        if (payload instanceof UDPPacket) {
            System.out.println("Analyzer - UDP Packet");
            if (packet.isLinkLocal(packet.getDestinationAddress())) {
                System.out.println("*** Link Local Message: Possibly Sleep???");
            } else {
                System.out.println("*** Message to/from Fiona");                
            }
        }
    }
    
    
    
    public static void main(String[] args) {
        TestSniff analyzer = new TestSniff();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        IEEE802154Handler i154Handler = new IEEE802154Handler();
        HC06Packeter hc06Packeter = new HC06Packeter();
        hc06Packeter.setContext(0, 0xaaaa0000, 0, 0, 0);
        try {
            while (true) {
                line = input.readLine();
                if (line != null && line.startsWith("h:")) {
                    /* HEX packet input */
                    byte[] data = Utils.hexconv(line.substring(2));
                    System.out.printf("Got packet of %d bytes\n", data.length);
                    System.out.println(line);
                    Packet packet = new Packet();
                    packet.setBytes(data);
                    i154Handler.packetReceived(packet);
//                    packet.printPacket();
//                    i154Handler.printPacket(System.out, packet);
                    if (analyzer != null) {
                        analyzer.analyzePacket(packet);
                    }
                    
                    if (packet.getPayloadLength() > 1 && 
                            packet.getAttributeAsInt(IEEE802154Handler.PACKET_TYPE) == IEEE802154Handler.DATAFRAME) {
                        IPv6Packet ipPacket = new IPv6Packet(packet);
                        int dispatch = packet.getData(0);
                        packet.setAttribute("6lowpan.dispatch", dispatch);
                        System.out.printf("Dispatch: %02x\n", dispatch & 0xff);
                        if (hc06Packeter.parsePacketData(ipPacket)) {
                            boolean more = true;
                            byte nextHeader = ipPacket.getNextHeader();
                            IPv6ExtensionHeader extHeader = null;
                            while(more) {
                                System.out.printf("Next Header: %d pos:%d\n", nextHeader, ipPacket.getPos());
                                ipPacket.printPayload();
                                switch(nextHeader) {
                                case HopByHopOption.DISPATCH:
                                    HopByHopOption hbh = new HopByHopOption();
                                    hbh.parsePacketData(ipPacket);
                                    ipPacket.setIPPayload(hbh);
                                    extHeader = hbh;
                                    nextHeader = hbh.getNextHeader();
                                    break;
                                case UDPPacket.DISPATCH:
                                    if (ipPacket.getIPPayload() != null && ipPacket.getIPPayload() instanceof UDPPacket) {
                                        /* All done ? */
                                        System.out.println("All done - UDP already part of payload?");
                                        more = false;
                                    } else {
                                        UDPPacket udpPacket = new UDPPacket();
                                        udpPacket.parsePacketData(ipPacket);
                                        if (extHeader != null) {
                                            extHeader.setNext(udpPacket);
                                        } else {
                                            ipPacket.setIPPayload(udpPacket);
                                        }
                                        System.out.println("UDP Packet handled...");
                                        udpPacket.printPacket(System.out);
                                        more = false;
                                    }
                                    break;
                                case ICMP6Packet.DISPATCH:
                                    ICMP6Packet icmp6Packet = ICMP6Packet.parseICMP6Packet(ipPacket);
                                    if (extHeader != null) {
                                        extHeader.setNext(icmp6Packet);
                                    } else {
                                        ipPacket.setIPPayload(icmp6Packet);
                                    }
                                    System.out.println("ICMP6 packet handled...");
                                    icmp6Packet.printPacket(System.out);
                                    more = false;
                                default:
                                    more = false;
                                }
                            }
                            if (analyzer != null) {
                                analyzer.analyzeIPPacket(ipPacket);
                            }
                        }
                    } 
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


enum NodeState {
    FOUND, FIND, SLEEPING
}

public class Node implements NodeInterface, Runnable {

    private class QueueItem {
        int linkWeight;
        Message message;
        QueueItem(int linkWeight, Message message) {
            this.linkWeight = linkWeight;
            this.message = message;
        }
    }
    
    public int id;
    public NodeState state;
     
    public int fragmentLevel; 
    public int fragmentID;
    
    public int best_weight; // weight of current candidate MOE
    public int find_count;                  // number of report messages expected

    public List<Link> links;
    public Link best_link;  // local direction of candidate MOE
    private Link in_branch; // edge towards core (sense of direction)
    private Link test_edge; // edge checked whether other end in same fragment
    
    private BlockingQueue<QueueItem> rxQueue;
    private Queue<QueueItem> queue;

    public Node(int id) {
        this.id = id;
        this.state = NodeState.SLEEPING;
        this.fragmentLevel = 0;
        this.fragmentID = -1;
        this.best_weight =  Integer.MAX_VALUE;
        this.find_count = 0; 
        this.links = new ArrayList<Link>();
        this.best_link = null;
        this.in_branch = null;
        this.test_edge = null;
        this.rxQueue = new LinkedBlockingQueue<QueueItem>();  // MESSAGE RECEIVING FIFO
        this.queue = new LinkedList<QueueItem>();    
    }

    public int getID() {
        return id;
    }
    
    public void addLink(Link link) {
        this.links.add(link);
    }

    public Link getLink(int index) {
        return this.links.get(index);
    }

    private void check_queue(){
        int size = queue.size();
    	if (size != 0){
            for (int i = 0; i < size; i++) {
                QueueItem obj = queue.remove();
                execute(weightToLink(obj.linkWeight), obj.message);
            }
        }
    }

    public Link weightToLink(int weight) {
        Link link = null;
        for (Link l : this.links) {
            if (l.getWeight() == weight) link=l;
        }
        assert(link != null);
        return link;
    }

    public void run() {
        // System.out.println("Node " + id + " ready");
        while(true) {
            try {
                QueueItem rx = rxQueue.poll(7, TimeUnit.SECONDS);
                
                if (rx==null) {
                    System.out.println(this);
                    if(this.state == NodeState.FIND){findMoe();}
                    continue;
                    
                }
                onMessage(weightToLink(rx.linkWeight), rx.message);
                
            } catch (Exception e) {
                System.out.println("N"+id+" exception at rxQueue.take()\n"+e);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    public void sendMessage(Link link, Message message) {
        try {
            link.dst(id).onRecieve(link.getWeight(), message);
        } catch (Exception e) {
            System.out.println("@onSend from N" + id + " l/w" + link.weight + " " + message.type );
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void onRecieve(int rxLinkWeight, Message message) {
        try {
            this.rxQueue.put(new QueueItem(rxLinkWeight, message));
        } catch (Exception e) {
            System.out.println("N"+id+" exception at onReceive");
            System.out.println(e);
            System.exit(1);
        }
    }

    private void onMessage(Link link, Message message) {
        if (state == NodeState.SLEEPING) wakeup();
        execute(link, message);
        check_queue();
    }
    
    public void wakeup() {
        if (state != NodeState.SLEEPING) return;
        for (Link link : links) { 
            if (link.getWeight() < best_weight) {
                best_link = link;
                best_weight = link.getWeight();
            }
        }

        best_link.setState(LinkState.IN_MST);
        state = NodeState.FOUND;

        System.out.println("Node "+id+" awake");
        sendMessage(best_link, new Message(Type.CONNECT, this.fragmentLevel, this.fragmentID, this.state, best_weight));
    }
    
    public void execute(Link link, Message message) {
        switch (message.type) {
            case CONNECT:
                connect(link, message);
                break;

            case INITIATE:
                initiate(link, message);
                break;

            case TEST:
                test(link, message);
                break;

            case ACCEPT:
                onAccept(link, message);
                break;
        
            case REJECT:
                onReject(link, message);
                break;

            case REPORT:
                onReport(link, message);
                break;

            case ROOT_CHANGE:
                change_root();
                break;

            default:
                break;
        }
    }

    public void connect(Link link, Message message) {

        // ABSORB
        if (message.fragmentLevel < this.fragmentLevel) {
            System.out.println("N" + id  +" absorb link "+ link.getWeight());
            link.setState(LinkState.IN_MST);
            sendMessage(link, new Message(Type.INITIATE, fragmentLevel, fragmentID, state, best_weight));        
            if (this.state == NodeState.FIND) find_count++;        
        // ENQUEUE
        } else if (link.state == LinkState.UNKOWN) {
            queue.add(new QueueItem(link.getWeight(), message));
            
        // MERGE
        } else {
            System.out.println("N" + id  +" merge w/ link "+ link.getWeight());
            sendMessage(link, new Message( Type.INITIATE, fragmentLevel+1, link.getWeight(), NodeState.FIND, best_weight));
        }
    }
    
    public void initiate(Link link, Message message){

        this.fragmentID = message.fragmentID;
        this.fragmentLevel = message.fragmentLevel;
        this.state = message.state;
        this.in_branch = link;
        this.best_link = null;
        this.best_weight = Integer.MAX_VALUE;
        
        // Propagate INITIATE message (inside your fragment)
        for (Link l : links) {
            if (l.state == LinkState.IN_MST && l.weight != in_branch.weight) {
                sendMessage(l, new Message( Type.INITIATE, this.fragmentLevel, this.fragmentID, this.state, best_weight));                
                if (this.state == NodeState.FIND) this.find_count++;

            }
        }
        if (this.state == NodeState.FIND) {
            findMoe();
        }

    }
    
    // Return smallest unknown link
    private Link findMoeCandidate() {
        
        int minWeight = Integer.MAX_VALUE;
        Link candidate = null;
        
        for (Link link : links) {
            if (link.state == LinkState.UNKOWN && link.getWeight() < minWeight) {
                candidate = link;
                minWeight = link.getWeight();
            }
        }

        return candidate;
    }

    private void findMoe() {
        best_weight = Integer.MAX_VALUE;
        this.test_edge = findMoeCandidate();
        if (this.test_edge == null) {
            sendReport();
        } else {
            this.best_weight = this.test_edge.getWeight();
            sendMessage(this.test_edge, new Message(Type.TEST, fragmentLevel, fragmentID, state, this.best_weight));
        }
    }
    
    public void test(Link link, Message message) {

        // ENQUEUE
        if (this.fragmentLevel < message.fragmentLevel) {
            queue.add(new QueueItem(link.getWeight(), message));

        // ACCEPT
        } else if (message.fragmentID != this.fragmentID) {
            sendMessage(link, new Message(Type.ACCEPT, this.fragmentLevel, this.fragmentID, this.state, best_weight));

        } else {
            // REJECT
            if (link.state == LinkState.UNKOWN) {
                link.setState(LinkState.NOT_IN_MST);
                sendMessage(link, new Message(Type.REJECT, this.fragmentLevel, this.fragmentID, this.state, best_weight));
            }
            if (test_edge != null && test_edge.getWeight() != link.getWeight()) {
                sendMessage(link, new Message(Type.REJECT, this.fragmentLevel, this.fragmentID, this.state, best_weight));            
            }
        }
    }    
    
    public void onAccept(Link link, Message message) {
        this.test_edge = null;
        if (link.weight <= best_weight) {
            best_link = link;
            best_weight = link.getWeight();
        }
        sendReport();
    }  
    
    public void onReject(Link link, Message message) {
        if (link.state == LinkState.UNKOWN) {
            link.setState(LinkState.NOT_IN_MST);   
        }
        findMoe();
    }
   
    public void sendReport() {
        if (find_count == 0 && test_edge == null) {
            this.state = NodeState.FOUND;
            sendMessage(in_branch, new Message(Type.REPORT, fragmentLevel, fragmentID, state, best_weight));
        }
    }

    public void onReport(Link link, Message message) {

        // From your own subtree
        if (link.weight != in_branch.weight) {
            assert(find_count > 0);
            find_count--;   
            if (message.weight < best_weight) {
                this.best_weight = message.weight;
                this.best_link = link;
            }
            sendReport();

        // Other subtree -> still finding MOE
        } else if (this.state == NodeState.FIND){
            queue.add(new QueueItem(link.getWeight(), message)); 
        
        // Other subtree -> found MOE
        } else {
    
            // Your subtree has the MOE
            if (message.weight > best_weight) {
                change_root();
    
            // Other subtree has the MOE
            } else if (message.weight == Integer.MAX_VALUE && best_weight == Integer.MAX_VALUE ) {
                System.out.println("N" + id + " halt");                        
            }
        }
    }

    private void change_root(){
        if(best_link.state == LinkState.IN_MST) sendMessage(best_link, new Message(Type.ROOT_CHANGE, fragmentLevel, fragmentID, state, best_weight));
        else{
            best_link.setState(LinkState.IN_MST);
            sendMessage(best_link, new Message(Type.CONNECT, fragmentLevel, fragmentID, state, best_weight));    
        }
    }
    
    public String toString() {
        String string =  id + " " + state + " fragmentID=" + fragmentID + " fragmentLevel=" + fragmentLevel + " best_weight=" + best_weight;
        return string;
    }
}
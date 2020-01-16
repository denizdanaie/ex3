import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


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
    private Queue<QueueItem> queue;

    public int id;
    public NodeState state;
     
    public int fragmentLevel; 
    public int fragmentID;
    
    public int best_weight = Integer.MAX_VALUE; // weight of current candidate MOE
    public int find_count = 0;                  // number of report messages expected

    public List<Link> links;
    public Link best_link;  // local direction of candidate MOE
    private Link in_branch; // edge towards core (sense of direction)
    private Link test_edge; // edge checked whether other end in same fragment
    
    private BlockingQueue<QueueItem> rxQueue;

    public Node(int id) {
        this.id = id;
        this.state = NodeState.SLEEPING;
        this.fragmentLevel = 0;
        this.fragmentID = -1;
        this.links = new ArrayList<Link>();
        this.queue = new LinkedList<QueueItem>();
        
        this.rxQueue = new LinkedBlockingQueue<QueueItem>();  // MESSAGE RECEIVING FIFO
    }

    public void run() {
        // System.out.println("Node " + id + " ready");

        while(true) {
            try {
                QueueItem rx = rxQueue.take();
                onMessage(weightToLink(rx.linkWeight), rx.message);
            
            } catch (Exception e) {
                System.out.println("N"+id+" exception at rxQueue.take()\n"+e);
            }
        }
    }

    public void addLink(Link link) {
        this.links.add(link);
    }

    public Link getLink(int index) {
        return this.links.get(index);
    }

    public int getID() {
        return id;
    }

    private void check_queue() {
    	if (queue.size() != 0) {
    	    for (int i = 0; i < queue.size(); i++) {
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

    public void sendMessage(Link link, Message message) {
        
        try {
            link.dst(id).onRecieve(link.getWeight(), message);
        } catch (Exception e) {
            System.out.println("@onSend from " + id + " exception: " + e);
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
        }
    }

    private void onMessage(Link link, Message message) {
        if (state == NodeState.SLEEPING) wakeup();
        execute(link, message);
        // check_queue();
    }
    
    public void execute(Link link, Message message) { 
        switch (message.type) {
            case TEST:
                test(link, message);
                break;

            case ACCEPT:
                accept(link, message);
                break;
        
            case REJECT:
                reject(link, message);
                break;

            case INITIATE:
                initiate(link, message);
                break;

            case REPORT:
                report(link, message);
                break;

            case CONNECT:
                connect(link, message);
                break;

            case ROOT_CHANGE:

                break;

            default:
                break;
        }
    }

    public void connect(Link link, Message message) {

        // System.out.println("N" + id  +" recieved CONNECT at link "+ link.getWeight());

        // ABSORB
        if (message.fragmentLevel < this.fragmentLevel) {
            System.out.println("N" + id  +" absorb link "+ link.getWeight());
            link.setState(LinkState.IN_MST);
            sendMessage(link, new Message(Type.INITIATE, fragmentLevel, fragmentID, state, best_weight));
            if (this.state == NodeState.FIND) find_count++;
        
        // ENQUEUE
        } else if (link.state == LinkState.UNKOWN) {
            System.out.println("connect-UNKOWN");
            queue.add(new QueueItem(link.getWeight(), message));

        // MERGE
        } else {
            System.out.println("N" + id  +" merge w/ link "+ link.getWeight());
            this.fragmentLevel++;
            this.fragmentID = link.getWeight();
            this.state = NodeState.FIND;        // Why was this commented out?
            this.in_branch = link;
            
            // System.out.println("N" + id + " fragmentLevel= " + fragmentLevel + " fragmentID= " + fragmentID);
            sendMessage(link, new Message( Type.INITIATE, fragmentLevel, fragmentID, NodeState.FIND, best_weight));
        }
        
    }
    
    public void initiate(Link link, Message message){
    
        // System.out.println("Node " + id + " recieved INITIATE");

        this.fragmentID = message.fragmentID;
        this.fragmentLevel = message.fragmentLevel;
        this.state = message.state;
        this.in_branch = link;
        this.best_link = null;
        this.best_weight = Integer.MAX_VALUE;
        // System.out.println("N" + id + " fragmentLevel= " + fragmentLevel + " fragmentID= " + fragmentID);
        // check_queue();
        for (Link l : links) {
            if (l.state == LinkState.IN_MST && l.weight != in_branch.weight) {
                sendMessage(l, new Message( Type.INITIATE, this.fragmentLevel, this.fragmentID, this.state, best_weight));
                if(this.state == NodeState.FIND){
                    this.find_count++;
                }
            }
        }

        if (this.state == NodeState.FIND) {
            find_MOE();
        }
    }
    
    private void find_MOE(){
        best_weight = Integer.MAX_VALUE;
        boolean localFlag = false;
        for (Link link : links) {
            if(link.state == LinkState.UNKOWN){
                localFlag = true;
                if (link.getWeight() < best_weight) {
                    this.test_edge = link;
                    best_weight = link.getWeight();
                }
            }
        }
        if(localFlag){
            // System.out.println("N" + id + " sends test w/link" + test_edge.getWeight());
            sendMessage(this.test_edge, new Message(Type.TEST, fragmentLevel, fragmentID, state, best_weight));
                    
        }else{
            test_edge = null;
            sendReport();
            
        }             
    }
    public void sendReport() {
        // if (id == 1)  System.out.println(test_edge);
        if (find_count == 0 && test_edge == null) {
            this.state = NodeState.FOUND;
            sendMessage(in_branch, new Message(Type.REPORT, fragmentLevel, fragmentID, state, best_weight));
            // check_queue();
        }
    }
    public void test(Link link, Message message) {
        
        // System.out.println("N" + id + " recieves TEST");
        if(this.fragmentLevel < message.fragmentLevel) {
            queue.add(new QueueItem(link.getWeight(), message));

        } else {
            if (message.fragmentID != this.fragmentID) {
                sendMessage(link, new Message(Type.ACCEPT, this.fragmentLevel, this.fragmentID, this.state, best_weight));
            } else {  
                if (link.state == LinkState.UNKOWN) {
                    link.setState(LinkState.NOT_IN_MST);
                    sendMessage(link, new Message(Type.REJECT, this.fragmentLevel, this.fragmentID, this.state, best_weight));
                }    
                if (test_edge.weight !=link.weight) {                    
                    sendMessage(link, new Message(Type.REJECT, this.fragmentLevel, this.fragmentID, this.state, best_weight));
                } else {
                    find_MOE(); //sure??
                }
            }

        }
    }    
    
    public void accept(Link link, Message message){
        
        this.test_edge = null;
        if(link.weight < this.best_weight){
            best_link = link;
            best_weight = link.getWeight();
        }
        sendReport();
    }  
    
    public void reject(Link link, Message message){
        if(link.state == LinkState.UNKOWN){
        link.setState(LinkState.NOT_IN_MST);    
        }
        find_MOE();
    }


    public void report(Link link, Message message) {
        if(link.weight != in_branch.weight){
            if(find_count>0) find_count--;           
            if(message.weight < best_weight){
                this.best_weight = message.weight;
                this.best_link = link;
            }
            sendReport();
        }else{  
            if(this.state == NodeState.FIND){
                // System.out.println("N" + id + " queued " + message.type + " w/ link" +  link.getWeight());
                // System.out.println("N" + id + " REPORT msg queued");
                queue.add(new QueueItem(link.getWeight(), message));
            }
            else{
                System.out.println("Halt");
                if(message.weight > best_weight)
                    change_root();
                else{
                    if(message.weight == Integer.MAX_VALUE && best_weight == Integer.MAX_VALUE ){
                        
                        System.exit(1);
                    }
                }
            }
        }
    }

    private void change_root(){}
    
    public String toString() {
        String string =  id + " " + state + " fragmentLevel=" + fragmentLevel + " fragmentID=" + fragmentID + " best_weight=" + best_weight;
        return string;
    }
}
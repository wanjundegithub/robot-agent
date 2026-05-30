package robot.agent.channel.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class UserMessageMailbox {

    private final BlockingQueue<UserMessage> queue;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public UserMessageMailbox(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    public boolean offer(UserMessage message) {
        return queue.offer(message);
    }

    public UserMessage poll() {
        return queue.poll();
    }

    public boolean startDraining() {
        return draining.compareAndSet(false, true);
    }

    public void stopDraining() {
        draining.set(false);
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}

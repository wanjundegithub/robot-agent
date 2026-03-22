package robot.agent.com.company.repository;

import org.springframework.stereotype.Repository;
import robot.agent.com.company.model.Order;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final ConcurrentMap<Long, Order> storage = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public Order save(Order order) {
        Order persisted = order.id() == null
                ? order.withId(sequence.incrementAndGet())
                : order;

        storage.put(persisted.id(), persisted);
        return persisted;
    }

    @Override
    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<Order> findAll() {
        return storage.values().stream()
                .sorted(Comparator.comparing(Order::id))
                .toList();
    }

    @Override
    public void deleteById(Long id) {
        storage.remove(id);
    }

    @Override
    public void deleteAll() {
        storage.clear();
        sequence.set(0);
    }
}

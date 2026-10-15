package payouts.payout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryPayoutStore implements PayoutStore {

    private final Map<String, Payout> byId = new HashMap<>();
    private final Map<String, String> idByKey = new HashMap<>();

    @Override
    public synchronized Optional<Payout> findById(String payoutId) {
        return Optional.ofNullable(byId.get(payoutId));
    }

    @Override
    public synchronized Optional<Payout> findByIdempotencyKey(String merchantId, String idempotencyKey) {
        String id = idByKey.get(scopedKey(merchantId, idempotencyKey));
        return id == null ? Optional.empty() : Optional.of(byId.get(id));
    }

    @Override
    public synchronized List<Payout> findByMerchant(String merchantId) {
        return byId.values().stream().filter(p -> p.request().merchantId().equals(merchantId)).toList();
    }

    @Override
    public synchronized void save(Payout payout) {
        byId.put(payout.id(), payout);
        idByKey.put(scopedKey(payout.request().merchantId(), payout.idempotencyKey()), payout.id());
    }

    private static String scopedKey(String merchantId, String idempotencyKey) {
        return merchantId + "\u0000" + idempotencyKey;
    }
}

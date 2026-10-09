package io.paysettle.ach.batch;

import io.paysettle.ach.AchRepository;
import io.paysettle.ach.nacha.NachaFile;
import io.paysettle.ach.nacha.NachaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

/**
 * Streams entry detail records of a stored NACHA file. The position is saved in the step's ExecutionContext
 * after every chunk, so a failed job restarts from the last committed chunk instead of re-processing entries.
 */
public class NachaEntryReader implements ItemStreamReader<AchEntryItem> {

    private static final String POSITION = "nacha.entry.position";

    private final AchRepository repo;
    private final UUID fileId;
    private List<AchEntryItem> items = List.of();
    private int position;

    public NachaEntryReader(AchRepository repo, UUID fileId) {
        this.repo = repo;
        this.fileId = fileId;
    }

    @Override
    public void open(ExecutionContext context) {
        NachaFile file = NachaParser.parse(repo.fileContent(fileId));
        List<AchEntryItem> all = new ArrayList<>(file.entryCount());
        for (NachaFile.Batch b : file.batches()) {
            for (NachaFile.Entry e : b.entries()) {
                all.add(new AchEntryItem(fileId, b.header(), e));
            }
        }
        this.items = all;
        this.position = context.getInt(POSITION, 0);
    }

    @Override
    public AchEntryItem read() {
        return position < items.size() ? items.get(position++) : null;
    }

    @Override
    public void update(ExecutionContext context) {
        context.putInt(POSITION, position);
    }
}

package io.paysettle.ach.batch;

import io.paysettle.ach.nacha.NachaFile;
import java.util.UUID;

/** One entry detail record with the batch header it belongs to. */
public record AchEntryItem(UUID fileId, NachaFile.BatchHeader batch, NachaFile.Entry entry) {
}

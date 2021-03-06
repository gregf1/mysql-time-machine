package com.booking.replication.augmenter;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by bdevetak on 20/11/15.
 */
public class AugmentedRowsEvent {

    private List<AugmentedRow> singleRowEvents = new ArrayList<AugmentedRow>();

    public void addSingleRowEvent(AugmentedRow au) {
        singleRowEvents.add(au);
    }

    public List<AugmentedRow> getSingleRowEvents() {
        return singleRowEvents;
    }
}

package com.horizen.examples;

import com.horizen.block.SidechainBlock;
import com.horizen.box.Box;
import com.horizen.proposition.Proposition;
import com.horizen.state.ApplicationState;
import com.horizen.state.SidechainStateReader;
import com.horizen.transaction.BoxTransaction;
import scala.util.Success;
import scala.util.Try;

import java.util.List;

// There is no custom logic for Car registry State now.
// TODO: prevent the declaration of CarBoxes which car information already exists in the previously added CarBoxes or CarSellOrderBoxes.
public class CarRegistryApplicationState implements ApplicationState {
    @Override
    public boolean validate(SidechainStateReader stateReader, SidechainBlock block) {
        return true;
    }

    @Override
    public boolean validate(SidechainStateReader stateReader, BoxTransaction<Proposition, Box<Proposition>> transaction) {
        return true;
    }

    @Override
    public Try<ApplicationState> onApplyChanges(SidechainStateReader stateReader, byte[] version, List<Box<Proposition>> newBoxes, List<byte[]> boxIdsToRemove) {
        return new Success<>(this);
    }

    @Override
    public Try<ApplicationState> onRollback(byte[] version) {
        return new Success<>(this);
    }
}

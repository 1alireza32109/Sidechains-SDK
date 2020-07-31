package com.horizen.examples.car.box;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.horizen.box.AbstractNoncedBox;
import com.horizen.box.BoxSerializer;
import com.horizen.examples.car.box.data.CarSellOrderBoxData;
import com.horizen.examples.car.box.data.CarSellOrderBoxDataSerializer;
import com.horizen.examples.car.proposition.SellOrderProposition;
import com.horizen.serialization.Views;

import java.util.Arrays;

import static com.horizen.examples.car.box.CarRegistryBoxesIdsEnum.CarSellOrderBoxId;

@JsonView(Views.Default.class)
@JsonIgnoreProperties({"boxData", "carId"})
public final class CarSellOrderBox extends AbstractNoncedBox<SellOrderProposition, CarSellOrderBoxData, CarSellOrderBox> {

    public CarSellOrderBox(CarSellOrderBoxData boxData, long nonce) {
        super(boxData, nonce);
    }

    @Override
    public byte[] bytes() {
        return Bytes.concat(
                Longs.toByteArray(nonce),
                CarSellOrderBoxDataSerializer.getSerializer().toBytes(boxData)
        );
    }

    @Override
    public BoxSerializer serializer() {
        return CarSellOrderBoxSerializer.getSerializer();
    }

    @Override
    public byte boxTypeId() {
        return CarSellOrderBoxId.id();
    }

    public static CarSellOrderBox parseBytes(byte[] bytes) {
        long nonce = Longs.fromByteArray(Arrays.copyOf(bytes, Longs.BYTES));
        CarSellOrderBoxData boxData = CarSellOrderBoxDataSerializer.getSerializer().parseBytes(Arrays.copyOfRange(bytes, Longs.BYTES, bytes.length));

        return new CarSellOrderBox(boxData, nonce);
    }

    public CarSellOrderBoxData getBoxData() {
        return this.boxData;
    }

    public String getVin() {
        return boxData.getVin();
    }

    public int getYear() {
        return boxData.getYear();
    }

    public String getModel() {
        return boxData.getModel();
    }

    public String getColor() {
        return boxData.getColor();
    }

    public long getPrice() {
        return value();
    }

    public byte[] getCarId() {
        return Bytes.concat(
                getVin().getBytes(),
                Ints.toByteArray(getYear()),
                getModel().getBytes(),
                getColor().getBytes()
        );
    }
}

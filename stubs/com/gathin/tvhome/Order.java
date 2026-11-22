package com.gathin.tvhome;
import java.util.ArrayList;
import java.util.List;
public final class Order {
    public final String dock;
    public final int docked;
    public final List<String> keys;
    public Order(String dock,ArrayList<String> keys,int docked){this.dock=dock;this.keys=keys;this.docked=docked;}
}

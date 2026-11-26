package com.vincenthzr.locationspoofer.xposed.utils;

import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Copy-on-write listener storage which wakes the owner only when subscriptions change. */
public final class DemandList<E> extends CopyOnWriteArrayList<E> {
    private final Runnable onChanged;

    public DemandList(Runnable onChanged) { this.onChanged = onChanged; }

    private void changed() {
        // Subscription tracking must never make the intercepted application's API throw.
        try { onChanged.run(); } catch (Throwable ignored) { }
    }

    @Override public boolean add(E value) {
        boolean result = super.add(value); if (result) changed(); return result;
    }
    @Override public void add(int index, E value) { super.add(index, value); changed(); }
    @Override public boolean addIfAbsent(E value) {
        boolean result = super.addIfAbsent(value); if (result) changed(); return result;
    }
    @Override public int addAllAbsent(Collection<? extends E> values) {
        int result = super.addAllAbsent(values); if (result != 0) changed(); return result;
    }
    @Override public boolean addAll(Collection<? extends E> values) {
        boolean result = super.addAll(values); if (result) changed(); return result;
    }
    @Override public boolean addAll(int index, Collection<? extends E> values) {
        boolean result = super.addAll(index, values); if (result) changed(); return result;
    }
    @Override public boolean remove(Object value) {
        boolean result = super.remove(value); if (result) changed(); return result;
    }
    @Override public E remove(int index) { E result = super.remove(index); changed(); return result; }
    @Override public boolean removeAll(Collection<?> values) {
        boolean result = super.removeAll(values); if (result) changed(); return result;
    }
    @Override public boolean retainAll(Collection<?> values) {
        boolean result = super.retainAll(values); if (result) changed(); return result;
    }
    @Override public boolean removeIf(Predicate<? super E> filter) {
        boolean result = super.removeIf(filter); if (result) changed(); return result;
    }
    @Override public void clear() {
        boolean hadValues = !isEmpty(); super.clear(); if (hadValues) changed();
    }
    @Override public E set(int index, E value) {
        E result = super.set(index, value); if (result != value) changed(); return result;
    }
    @Override public void replaceAll(UnaryOperator<E> operator) { super.replaceAll(operator); changed(); }
    @Override public void sort(Comparator<? super E> comparator) { super.sort(comparator); changed(); }
}

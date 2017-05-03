/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.openremote.model.value.impl;

import org.openremote.model.value.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ArrayValueImpl extends ValueImpl implements ArrayValue {

    final private transient ValueFactory factory;
    private transient ArrayList<Value> values = new ArrayList<>();

    public ArrayValueImpl(ValueFactory factory) {
        this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Value> Optional<T> get(int index) {
        return index >= 0 && values.size() > index ? Optional.of((T) values.get(index)) : Optional.empty();
    }

    @Override
    public List<Object> getObject() {
        List<Object> objs = new ArrayList<>();
        for (Value val : values) {
            objs.add(((ValueImpl) val).getObject());
        }
        return objs;
    }

    @Override
    public ValueType getType() {
        return ValueType.ARRAY;
    }

    @Override
    public int length() {
        return values.size();
    }

    @Override
    public void remove(int index) {
        values.remove(index);
    }

    @Override
    public void set(int index, Value value) {
        if (value == null && index >= 0 && index < values.size()) {
            values.remove(index);
        } else if (index == values.size()) {
            values.add(index, value);
        } else {
            values.set(index, value);
        }
    }

    @Override
    public void set(int index, String string) {
        set(index, factory.create(string));
    }

    @Override
    public void set(int index, double number) {
        set(index, factory.create(number));
    }

    @Override
    public void set(int index, boolean bool) {
        set(index, factory.create(bool));
    }

    @Override
    public String toJson() throws ValueException {
        return ValueUtil.stringify(this);
    }

    @Override
    public void traverse(ValueVisitor visitor, ValueContext ctx) throws ValueException {
        if (visitor.visit(this, ctx)) {
            ArrayValueContext arrayCtx = new ArrayValueContext(this);
            for (int i = 0; i < length(); i++) {
                arrayCtx.setCurrentIndex(i);
                if (visitor.visitIndex(arrayCtx.getCurrentIndex(), arrayCtx)) {
                    Optional<Value> value = get(i);
                    if (value.isPresent()) {
                        visitor.accept(value.get(), arrayCtx);
                        arrayCtx.setFirst(false);
                    }
                }
            }
        }
        visitor.endVisit(this, ctx);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null)
            return false;
        if (!(o instanceof ArrayValueImpl))
            return false;
        ArrayValueImpl that = (ArrayValueImpl) o;

        if (length() != that.length())
            return false;
        for (int i = 0; i < length(); i++) {
            if (!get(i).equals(that.get(i)))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 31;
        result = result * 5;
        for (int i = 0; i < length(); i++) {
            result = result * 5 + (i + get(i).hashCode());
        }
        return result;
    }

}

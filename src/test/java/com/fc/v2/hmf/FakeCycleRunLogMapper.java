package com.fc.v2.hmf;

import java.io.Serializable;

import com.fc.v2.mapper.auto.THmfCycleRunLogMapper;
import com.fc.v2.model.auto.THmfCycleRunLog;

/** 结息催交轮次台账内存假 Mapper。 */
class FakeCycleRunLogMapper extends InMemoryMapper<THmfCycleRunLog> implements THmfCycleRunLogMapper {

    @Override
    Serializable idOf(THmfCycleRunLog t) {
        return t.getId();
    }

    @Override
    void assignId(THmfCycleRunLog t, long id) {
        t.setId(id);
    }
}

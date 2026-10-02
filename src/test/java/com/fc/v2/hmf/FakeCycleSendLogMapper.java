package com.fc.v2.hmf;

import java.io.Serializable;

import com.fc.v2.mapper.auto.THmfCycleSendLogMapper;
import com.fc.v2.model.auto.THmfCycleSendLog;

/** 结息催交送达台账内存假 Mapper。 */
class FakeCycleSendLogMapper extends InMemoryMapper<THmfCycleSendLog> implements THmfCycleSendLogMapper {

    @Override
    Serializable idOf(THmfCycleSendLog t) {
        return t.getId();
    }

    @Override
    void assignId(THmfCycleSendLog t, long id) {
        t.setId(id);
    }
}

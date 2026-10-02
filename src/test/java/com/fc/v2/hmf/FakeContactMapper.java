package com.fc.v2.hmf;

import java.io.Serializable;

import com.fc.v2.mapper.auto.THmfContactMapper;
import com.fc.v2.model.auto.THmfContact;

/** 业委会联系人内存假 Mapper。 */
class FakeContactMapper extends InMemoryMapper<THmfContact> implements THmfContactMapper {

    @Override
    Serializable idOf(THmfContact t) {
        return t.getId();
    }

    @Override
    void assignId(THmfContact t, long id) {
        t.setId(id);
    }
}

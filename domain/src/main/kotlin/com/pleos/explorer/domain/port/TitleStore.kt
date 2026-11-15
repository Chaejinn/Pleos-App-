package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate

interface TitleStore {
    /** 확정 REGION 수·신규 거리 누적·획득 칭호를 동일한 revision의 snapshot으로 읽는다. */
    suspend fun load(): TitleSnapshot

    /** revision 비교·칭호 최초 획득·보상·다음 snapshot을 한 트랜잭션으로 저장한다. */
    suspend fun commit(expectedRevision: Long, update: TitleUpdate)
}

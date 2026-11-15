package com.pleos.explorer.domain.port

import com.pleos.explorer.domain.model.TitleSnapshot
import com.pleos.explorer.domain.model.TitleUpdate

interface TitleStore {
    /** 확정 REGION 수·신규 거리 누적·해안 방문 근거·획득 칭호를 동일한 revision으로 읽는다. */
    suspend fun load(): TitleSnapshot

    /** revision 비교·방문 진행·칭호 최초 획득·보상·다음 snapshot을 한 트랜잭션으로 저장한다. */
    suspend fun commit(expectedRevision: Long, update: TitleUpdate)
}

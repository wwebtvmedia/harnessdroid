// OSP-Lite service contract (osp_lite_v05.html §5). Mirrors the AIDL published
// by the OSP Bridge app (swarmknowledge_protocol/android/ospbridge): the
// package must stay identical so the Binder descriptors match across processes.
package com.swarmknowledge.ospbridge;

import com.swarmknowledge.ospbridge.IOspCallback;

interface IOspService {
    String submitQuery(in String text, in int stakesTier);      // → queryId
    void registerCallback(in String queryId, in IOspCallback cb);
    void advertiseCentroid(in byte[] cborCodebook);              // ≤ 1 KB
    byte[] fetchKeyBundle(in String nodeId);                     // signer key bundle (dev: HMAC id)
}

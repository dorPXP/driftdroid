// Native call catalog used by InvokeDirectCpu. Keep project-specific HLE
// address mappings here; abi_bridge.h contains only the generic ABI machinery.
//
// This is a .inc, not a header: it has no includes of its own and references
// CpuContext, KnownTypedNativeCpuCall<> and KnownNativeCpuCall<>, all of which
// are declared above its single mid-file include site in abi_bridge.h. It
// cannot compile standalone and must never be included anywhere else.

extern "C" void nw4r__lyt__detail__DrawQuad_800847c0(CpuContext* ctx);
extern "C" void nw4r__lyt__detail__DrawQuad_80084d20(CpuContext* ctx);

extern "C" void GX__SetVtxDesc_8016d3a4(uint32_t a, uint32_t t);
extern "C" void GX__ClearVtxDesc_8016dc34();
extern "C" void GX__SetVtxAttrFmt_8016dc68(uint32_t vf, uint32_t a, uint32_t c, uint32_t t, uint32_t fr);
extern "C" void GX__SetTexCoordGen2_8016e37c(uint32_t dc, uint32_t f, uint32_t sp, uint32_t m, uint32_t n, uint32_t pm);
extern "C" void GX__SetNumTexGens_8016e5a4(uint32_t n);
extern "C" void GX__DrawDone_8016eab0();
extern "C" void GX__Begin_8016f0f0(uint32_t t, uint32_t vf, uint32_t nv);
extern "C" void GX__SetLineWidth_8016f314(uint32_t width, uint32_t texOffsets);
extern "C" void GX__SetPointSize_8016f348(uint32_t pointSize, uint32_t texOffsets);
extern "C" void GX__SetCullMode_8016f3b8(uint32_t m);
extern "C" void GX__SetNumChans_8017054c(uint32_t n);
extern "C" void GX__SetChanCtrl_80170570(uint32_t ch, uint32_t en, uint32_t as, uint32_t ms, uint32_t lm, uint32_t df, uint32_t af);
extern "C" void GX__InitTexObj_801707f8(uint32_t oa, uint32_t da, uint32_t w, uint32_t h, uint32_t f, uint32_t ws, uint32_t wt, uint32_t m);
extern "C" void GX__InitTexObjLOD_80170a4c(uint32_t oa, uint32_t mif, uint32_t maf, float mil, float mal, float lb, uint32_t bc, uint32_t el, uint32_t ma);
extern "C" void GX__InvalidateTexAll_80171110();
extern "C" void GX__LoadTexObj_80170f2c(uint32_t oa, uint32_t tid);
extern "C" void GX__SetNumIndStages_80171b38(uint32_t n);
extern "C" void GX__SetTevDirect_80171b58(uint32_t s);
extern "C" void GX__SetTevColorIn_80171ce0(uint32_t s, uint32_t a, uint32_t b, uint32_t c, uint32_t d);
extern "C" void GX__SetTevAlphaIn_80171d20(uint32_t s, uint32_t a, uint32_t b, uint32_t c, uint32_t d);
extern "C" void GX__SetTevColorOp_80171d60(uint32_t s, uint32_t op, uint32_t b, uint32_t sc, uint32_t cl, uint32_t or_);
extern "C" void GX__SetTevAlphaOp_80171db8(uint32_t s, uint32_t op, uint32_t b, uint32_t sc, uint32_t cl, uint32_t or_);
extern "C" void GX__SetTevColorS10_80171e70(uint32_t id, uint32_t cp);
extern "C" void GX__SetTevKColor_80171ed4(uint32_t id, uint32_t cp);
extern "C" void GX__SetTevKColorSel_80171f30(uint32_t s, uint32_t sel);
extern "C" void GX__SetTevKAlphaSel_80171f80(uint32_t s, uint32_t sel);
extern "C" void GX__SetTevSwapMode_80171fd0(uint32_t s, uint32_t rs, uint32_t ts);
extern "C" void GX__SetTevSwapModeTable_8017200c(uint32_t id, uint32_t r, uint32_t g, uint32_t b, uint32_t a);
extern "C" void GX__SetTevOrder_8017214c(uint32_t s, uint32_t c, uint32_t m, uint32_t col);
extern "C" void GX__SetNumTevStages_801722a8(uint32_t n);
extern "C" void GX__SetBlendMode_8017277c(uint32_t t, uint32_t s, uint32_t d, uint32_t op);
extern "C" void GX__LoadPosMtxImm_8017310c(uint32_t ma, uint32_t id);
extern "C" void GX__SetCurrentMtx_80173214(uint32_t id);

// Leaf natives added 2026-10-11 (see MKW_KNOWN_LEAF_NATIVE below).
extern "C" int32_t OS__DisableInterrupts_801a65ac();
extern "C" int32_t OS__EnableInterrupts_801a65c0();
extern "C" int32_t OS__RestoreInterrupts_801a65d4(int32_t level);
// The same two as called by translated code: a guest-level return to "interrupts on" is also where
// the emulation thread stops for the sound host thread when that one asks (sound_core.h).
extern "C" int32_t OS__EnableInterrupts_FromGuest();
extern "C" int32_t OS__RestoreInterrupts_FromGuest(int32_t level);
extern "C" void GX__LoadNrmMtxImm_80173188(uint32_t ma, uint32_t id);
extern "C" void GX__LoadTexMtxImm_80173234(uint32_t ma, uint32_t id, uint32_t t);
extern "C" void GX__LoadPosMtxIndx_8017315c(uint32_t mi, uint32_t id);
extern "C" void GX__LoadNrmMtxIndx3x3_801731e0(uint32_t mi, uint32_t id);
extern "C" void GX__SetArray_8016e32c(uint32_t a, uint32_t ba, uint32_t str);
extern "C" void GX__SetArray_8016e1c4(uint32_t a, uint32_t b, uint32_t s);
extern "C" void GX__GetVtxDesc_8016d9f0(uint32_t a, uint32_t tp);
extern "C" void GX__GetVtxDescv_8016dba4(uint32_t la);
extern "C" void GX__SetVtxDescv_8016d608(uint32_t la);
extern "C" void GX__SetVtxAttrFmtv_8016de08(uint32_t vf, uint32_t la);
extern "C" void GX__GetVtxAttrFmt_8016e04c(uint32_t vf, uint32_t a, uint32_t cp, uint32_t tp, uint32_t fp);
extern "C" void GX__GetVtxAttrFmtv_8016e2b8(uint32_t vf, uint32_t la);
extern "C" void GX__EnableTexOffsets_8016f37c(uint32_t coord, uint32_t lineEnable, uint32_t pointEnable);
extern "C" void GX__SetZMode_80172824(uint32_t ce, uint32_t f, uint32_t ue);
extern "C" void GX__SetZCompLoc_80172858(uint32_t bt);
extern "C" void GX__SetAlphaCompare_80172088(uint32_t c0, uint32_t r0, uint32_t op, uint32_t c1, uint32_t r1);
extern "C" void GX__SetColorUpdate_801727cc(uint32_t en);
extern "C" void GX__SetAlphaUpdate_801727f8(uint32_t en);
extern "C" void GX__SetDstAlpha_8017295c(uint32_t en, uint32_t a);
extern "C" void GX__SetFog_801722cc(uint32_t t, float sz, float ez, float nz, float fz, uint32_t cp);
extern "C" void GX__SetZTexture_801720c0(uint32_t op, uint32_t f, uint32_t b);
extern "C" void GX__SetChanAmbColor_8017039c(uint32_t c, uint32_t cp);
extern "C" void GX__SetChanMatColor_80170474(uint32_t c, uint32_t cp);
extern "C" void GX__LoadLightObjImm_80170320(uint32_t la, uint32_t lid);
extern "C" void GX__SetTevOp_80171c4c(uint32_t s, uint32_t m);
extern "C" void GX__SetTevColor_80171e10(uint32_t id, uint32_t cp);
extern "C" void GX__SetIndTexOrder_80171a6c(uint32_t s, uint32_t c, uint32_t m);
extern "C" void GX__SetIndTexCoordScale_80171968(uint32_t s, uint32_t ss, uint32_t ts);
extern "C" void GX__SetIndTexMtx_80171814(uint32_t id, uint32_t ma, uint32_t se);
extern "C" void GX__SetTevIndWarp_80171ba0(uint32_t ts, uint32_t is, uint32_t so, uint32_t rm, uint32_t ms);
extern "C" void GX__SetTexCoordScaleManually_80171180(uint32_t c, uint32_t en, uint32_t ss, uint32_t ts);
extern "C" void GX__SetTexCoordBias_801711fc(uint32_t c, uint32_t se, uint32_t te);
extern "C" void GX__LoadTlut_80170fa8(uint32_t oa, uint32_t tl);
extern "C" void GX__InitTlutObj_80170f80(uint32_t oa, uint32_t da, uint32_t f, uint32_t e);
extern "C" void GX__InitTexObjWrapMode_80170b50(uint32_t oa, uint32_t ws, uint32_t wt);
extern "C" void GX__InitTexObjTlut_80170b64(uint32_t oa, uint32_t tl);
extern "C" void GX__InitTexObjFilter_80170b6c(uint32_t oa, uint32_t minFilter, uint32_t magFilter);
extern "C" void GX__InitTexObjLODBias_80170b94(uint32_t oa, float bias);
extern "C" void GX__InitTexObjUserData_80170be8(uint32_t oa, uint32_t userData);
extern "C" void GX__SetProjection_8017301c(uint32_t ma, uint32_t pt);
extern "C" void GX__SetProjectionv_80173080(uint32_t pa);
extern "C" void GX__GetProjectionv_801730cc(uint32_t pa);
extern "C" void GX__SetScissor_80173430(uint32_t l, uint32_t t, uint32_t w, uint32_t h);
extern "C" void GX__SetViewport_801733b4(float l, float t, float w, float h, float nz, float fz);
extern "C" void GX__SetViewportJitter_80173378(float l, float t, float w, float h, float nz, float fz, uint32_t f);
extern "C" void GX__GetViewportv_801733e0(uint32_t oa);

#define MKW_KNOWN_TYPED_NATIVE(addr, fn, ...) \
template <> struct KnownTypedNativeCpuCall<addr> { \
    static constexpr bool kAvailable = true; \
    static constexpr bool kLeaf = false; \
    static void Invoke(CpuContext* cpu) { (void)cpu; fn(__VA_ARGS__); } \
}

// A leaf native takes its arguments by value, never sees the CpuContext, and neither runs guest
// code nor lets another guest thread run before it returns. It therefore cannot change r14-r31,
// so InvokeDirectCpu calls it bare: no copy of the 18 nonvolatile registers out and back, no
// context-scope test. These are the natives translated code calls thousands of times a frame
// (every material's GX state, and the interrupt disable/restore pairs around almost everything
// nw4r::snd does), where that bookkeeping cost more than the call. Do NOT list a native here
// that can reach the scheduler, a guest callback or the frame/timing services (GXBegin,
// GXCallDisplayList, GXDrawDone, GXCopyDisp, anything that can sleep).
#define MKW_KNOWN_LEAF_NATIVE(addr, fn, ...) \
template <> struct KnownTypedNativeCpuCall<addr> { \
    static constexpr bool kAvailable = true; \
    static constexpr bool kLeaf = true; \
    static void Invoke(CpuContext* cpu) { (void)cpu; fn(__VA_ARGS__); } \
}
#define MKW_KNOWN_LEAF_NATIVE_RET(addr, fn, ...) \
template <> struct KnownTypedNativeCpuCall<addr> { \
    static constexpr bool kAvailable = true; \
    static constexpr bool kLeaf = true; \
    static void Invoke(CpuContext* cpu) { cpu->gpr[3] = static_cast<uint32_t>(fn(__VA_ARGS__)); } \
}

MKW_KNOWN_LEAF_NATIVE(0x8016D3A4u, GX__SetVtxDesc_8016d3a4, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016DC34u, GX__ClearVtxDesc_8016dc34);
MKW_KNOWN_LEAF_NATIVE(0x8016DC68u, GX__SetVtxAttrFmt_8016dc68, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x8016E37Cu, GX__SetTexCoordGen2_8016e37c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7], cpu->gpr[8]);
MKW_KNOWN_LEAF_NATIVE(0x8016E5A4u, GX__SetNumTexGens_8016e5a4, cpu->gpr[3]);
MKW_KNOWN_TYPED_NATIVE(0x8016EAB0u, GX__DrawDone_8016eab0);
MKW_KNOWN_TYPED_NATIVE(0x8016F0F0u, GX__Begin_8016f0f0, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8016F314u, GX__SetLineWidth_8016f314, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016F348u, GX__SetPointSize_8016f348, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016F3B8u, GX__SetCullMode_8016f3b8, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x8017054Cu, GX__SetNumChans_8017054c, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x80170570u, GX__SetChanCtrl_80170570, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7], cpu->gpr[8], cpu->gpr[9]);
MKW_KNOWN_LEAF_NATIVE(0x801707F8u, GX__InitTexObj_801707f8, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7], cpu->gpr[8], cpu->gpr[9], cpu->gpr[10]);
MKW_KNOWN_LEAF_NATIVE(0x80170A4Cu, GX__InitTexObjLOD_80170a4c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], static_cast<float>(cpu->fpr[1].d), static_cast<float>(cpu->fpr[2].d), static_cast<float>(cpu->fpr[3].d), cpu->gpr[6], cpu->gpr[7], cpu->gpr[8]);
MKW_KNOWN_LEAF_NATIVE(0x80171110u, GX__InvalidateTexAll_80171110);
MKW_KNOWN_LEAF_NATIVE(0x80170F2Cu, GX__LoadTexObj_80170f2c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171B38u, GX__SetNumIndStages_80171b38, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x80171B58u, GX__SetTevDirect_80171b58, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x80171CE0u, GX__SetTevColorIn_80171ce0, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x80171D20u, GX__SetTevAlphaIn_80171d20, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x80171D60u, GX__SetTevColorOp_80171d60, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7], cpu->gpr[8]);
MKW_KNOWN_LEAF_NATIVE(0x80171DB8u, GX__SetTevAlphaOp_80171db8, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7], cpu->gpr[8]);
MKW_KNOWN_LEAF_NATIVE(0x80171E70u, GX__SetTevColorS10_80171e70, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171ED4u, GX__SetTevKColor_80171ed4, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171F30u, GX__SetTevKColorSel_80171f30, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171F80u, GX__SetTevKAlphaSel_80171f80, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171FD0u, GX__SetTevSwapMode_80171fd0, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8017200Cu, GX__SetTevSwapModeTable_8017200c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x8017214Cu, GX__SetTevOrder_8017214c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6]);
MKW_KNOWN_LEAF_NATIVE(0x801722A8u, GX__SetNumTevStages_801722a8, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x8017277Cu, GX__SetBlendMode_8017277c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6]);
MKW_KNOWN_LEAF_NATIVE(0x8017310Cu, GX__LoadPosMtxImm_8017310c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80173214u, GX__SetCurrentMtx_80173214, cpu->gpr[3]);

MKW_KNOWN_LEAF_NATIVE_RET(0x801A65ACu, OS__DisableInterrupts_801a65ac);
MKW_KNOWN_LEAF_NATIVE_RET(0x801A65C0u, OS__EnableInterrupts_FromGuest);
MKW_KNOWN_LEAF_NATIVE_RET(0x801A65D4u, OS__RestoreInterrupts_FromGuest, static_cast<int32_t>(cpu->gpr[3]));
MKW_KNOWN_LEAF_NATIVE(0x80173188u, GX__LoadNrmMtxImm_80173188, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80173234u, GX__LoadTexMtxImm_80173234, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8017315Cu, GX__LoadPosMtxIndx_8017315c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x801731E0u, GX__LoadNrmMtxIndx3x3_801731e0, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016E32Cu, GX__SetArray_8016e32c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8016E1C4u, GX__SetArray_8016e1c4, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8016D9F0u, GX__GetVtxDesc_8016d9f0, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016DBA4u, GX__GetVtxDescv_8016dba4, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x8016D608u, GX__SetVtxDescv_8016d608, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x8016DE08u, GX__SetVtxAttrFmtv_8016de08, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016E04Cu, GX__GetVtxAttrFmt_8016e04c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x8016E2B8u, GX__GetVtxAttrFmtv_8016e2b8, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8016F37Cu, GX__EnableTexOffsets_8016f37c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80172824u, GX__SetZMode_80172824, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80172858u, GX__SetZCompLoc_80172858, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x80172088u, GX__SetAlphaCompare_80172088, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x801727CCu, GX__SetColorUpdate_801727cc, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x801727F8u, GX__SetAlphaUpdate_801727f8, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x8017295Cu, GX__SetDstAlpha_8017295c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x801722CCu, GX__SetFog_801722cc, cpu->gpr[3], static_cast<float>(cpu->fpr[1].d), static_cast<float>(cpu->fpr[2].d), static_cast<float>(cpu->fpr[3].d), static_cast<float>(cpu->fpr[4].d), cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x801720C0u, GX__SetZTexture_801720c0, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x8017039Cu, GX__SetChanAmbColor_8017039c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80170474u, GX__SetChanMatColor_80170474, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80170320u, GX__LoadLightObjImm_80170320, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171C4Cu, GX__SetTevOp_80171c4c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171E10u, GX__SetTevColor_80171e10, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80171A6Cu, GX__SetIndTexOrder_80171a6c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80171968u, GX__SetIndTexCoordScale_80171968, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80171814u, GX__SetIndTexMtx_80171814, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80171BA0u, GX__SetTevIndWarp_80171ba0, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6], cpu->gpr[7]);
MKW_KNOWN_LEAF_NATIVE(0x80171180u, GX__SetTexCoordScaleManually_80171180, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6]);
MKW_KNOWN_LEAF_NATIVE(0x801711FCu, GX__SetTexCoordBias_801711fc, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80170FA8u, GX__LoadTlut_80170fa8, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80170F80u, GX__InitTlutObj_80170f80, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6]);
MKW_KNOWN_LEAF_NATIVE(0x80170B50u, GX__InitTexObjWrapMode_80170b50, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80170B64u, GX__InitTexObjTlut_80170b64, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80170B6Cu, GX__InitTexObjFilter_80170b6c, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5]);
MKW_KNOWN_LEAF_NATIVE(0x80170B94u, GX__InitTexObjLODBias_80170b94, cpu->gpr[3], static_cast<float>(cpu->fpr[1].d));
MKW_KNOWN_LEAF_NATIVE(0x80170BE8u, GX__InitTexObjUserData_80170be8, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x8017301Cu, GX__SetProjection_8017301c, cpu->gpr[3], cpu->gpr[4]);
MKW_KNOWN_LEAF_NATIVE(0x80173080u, GX__SetProjectionv_80173080, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x801730CCu, GX__GetProjectionv_801730cc, cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x80173430u, GX__SetScissor_80173430, cpu->gpr[3], cpu->gpr[4], cpu->gpr[5], cpu->gpr[6]);
MKW_KNOWN_LEAF_NATIVE(0x801733B4u, GX__SetViewport_801733b4, static_cast<float>(cpu->fpr[1].d), static_cast<float>(cpu->fpr[2].d), static_cast<float>(cpu->fpr[3].d), static_cast<float>(cpu->fpr[4].d), static_cast<float>(cpu->fpr[5].d), static_cast<float>(cpu->fpr[6].d));
MKW_KNOWN_LEAF_NATIVE(0x80173378u, GX__SetViewportJitter_80173378, static_cast<float>(cpu->fpr[1].d), static_cast<float>(cpu->fpr[2].d), static_cast<float>(cpu->fpr[3].d), static_cast<float>(cpu->fpr[4].d), static_cast<float>(cpu->fpr[5].d), static_cast<float>(cpu->fpr[6].d), cpu->gpr[3]);
MKW_KNOWN_LEAF_NATIVE(0x801733E0u, GX__GetViewportv_801733e0, cpu->gpr[3]);

#undef MKW_KNOWN_TYPED_NATIVE
#undef MKW_KNOWN_LEAF_NATIVE
#undef MKW_KNOWN_LEAF_NATIVE_RET

#define MKW_KNOWN_NATIVE_CPU_CALL(addr, fn) \
template <> struct KnownNativeCpuCall<addr> { \
    static constexpr bool kAvailable = true; \
    static constexpr uint32_t kNonvolatileFprWriteMask = 0; \
    static constexpr void (*Entry)(CpuContext*) = &fn; \
}

MKW_KNOWN_NATIVE_CPU_CALL(0x800847C0u, nw4r__lyt__detail__DrawQuad_800847c0);
MKW_KNOWN_NATIVE_CPU_CALL(0x80084D20u, nw4r__lyt__detail__DrawQuad_80084d20);

#undef MKW_KNOWN_NATIVE_CPU_CALL

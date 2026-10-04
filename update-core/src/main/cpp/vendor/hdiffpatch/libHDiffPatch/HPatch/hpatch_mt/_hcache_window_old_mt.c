//  _hcache_window_old_mt.c
//  hpatch
/*
 The MIT License (MIT)
 Copyright (c) 2026 HouSisong
*/
#include "_hcache_window_old_mt.h"
#include "_patch_private_mt.h"
#include "../patch_private.h"
#if (_IS_USED_MULTITHREAD)

#define _kWinMask  (hpatch_kMaxWindowMetaCount-1)

// Ring-buffer old data cache manager with async preload thread
typedef struct hcache_window_old_mt_t{
    unsigned char*         buf; // Ring buffer
    size_t                 bufSize;

    // Prepared window batch info (ring array)
    _oldwin_info_t         winInfos[hpatch_kMaxWindowMetaCount];
    size_t                 winStart;          // ring start index (first unconsumed window)
    volatile size_t        winCount;          // number of unconsumed windows in ring
    volatile size_t        readyWinCount;     // number of ready windows from winStart

    // Protected region: written-but-unconsumed data in ring buffer
    // Async thread checks overlap against this before each window write
    volatile size_t        protectedBufPos; // bufPos of first unconsumed window
    volatile size_t        protectedLen;    // total bytes of written-but-unconsumed data
    hpatch_StreamPos_t     _reuseLen_back;
    hpatch_StreamPos_t     _noOverlapLeft_back;

    volatile hpatch_StreamPos_t leaveWindowCount;// Window count tracking
    hpatch_mt_base_t            mt_base;// Threading infrastructure
    const hpatch_TStreamInput*  old_stream;
} hcache_window_old_mt_t;

size_t hcache_window_old_mt_t_memSize(void) {
    return sizeof(hcache_window_old_mt_t);
}

// ---- ring buffer helpers ----

    static hpatch_force_inline hpatch_BOOL _buf_range_wraps(size_t pos, size_t len, size_t bufSize) {
        return (pos + len > bufSize);
    }

    static hpatch_force_inline void __swap(size_t* a,size_t* b) { size_t t=*a; *a=*b; *b=t; }
    static hpatch_force_inline void __swapb(unsigned char* a,unsigned char* b) { unsigned char t=*a; *a=*b; *b=t; }
    
    // Check if two ring buffer ranges overlap
    static hpatch_inline hpatch_BOOL _buf_ranges_overlap(size_t pos1,size_t len1,size_t pos2,size_t len2,size_t bufSize) {
        size_t end1=pos1+len1;
        size_t end2=pos2+len2;
        assert((pos1<bufSize)&&(pos2<bufSize));
        if ((len1==0) || (len2==0)) return hpatch_FALSE;
        if (pos1>pos2) {  __swap(&pos1,&pos2); __swap(&end1,&end2);  }
        //  [             bufSize                   ]
        //       {pos1          end1}  (pos2           end2)   
        return (pos2<end1)||(pos1+bufSize<end2);
    }

    static hpatch_force_inline size_t __ring_pos(size_t pos,size_t bufSize) { return (pos>=bufSize)?(pos-bufSize):pos; }

    static void _ring_memmove_to_right(unsigned char* buf,size_t bufSize,size_t srcPos,size_t dstPos,size_t len){
        assert(srcPos<=dstPos);
        srcPos=__ring_pos(srcPos,bufSize);
        dstPos=__ring_pos(dstPos,bufSize);
        if ((len==0)||(srcPos==dstPos)) return;
        {//back to front
            size_t s_idx = __ring_pos(srcPos+len-1,bufSize);
            size_t d_idx = __ring_pos(dstPos+len-1,bufSize);
            while (len > 0) {
                size_t chunk = ((s_idx<d_idx)?s_idx:d_idx)+1;
                chunk=(chunk<len)?chunk:len;
                memmove(buf+d_idx-chunk+1,buf+s_idx-chunk+1,chunk);
                s_idx = __ring_pos(s_idx + bufSize-chunk,bufSize);
                d_idx = __ring_pos(d_idx + bufSize-chunk,bufSize);
                len -= chunk;
            }
        }
    }

        static void __mem_reverse(unsigned char* bufBegin,unsigned char* bufEnd,size_t swapCount){
            while (swapCount--) __swapb(bufBegin++,--bufEnd);
        }
    static void _ring_reverse(unsigned char* buf,size_t bufSize,size_t pos,size_t len){
        pos=__ring_pos(pos,bufSize);
        if (pos+len<=bufSize){
            __mem_reverse(buf+pos,buf+pos+len,len/2);
        }else{
            size_t rlen=pos+len-bufSize;
            size_t llen=len-rlen;
            size_t mlen=(llen<=rlen)?llen:rlen;
            __mem_reverse(buf+pos,buf+rlen,mlen);
            if (llen<=rlen)
                __mem_reverse(buf,buf+(rlen-mlen),(rlen-mlen)/2);
            else
                __mem_reverse(buf+pos+mlen,buf+bufSize,(llen-mlen)/2);
        }
    }

    static void _ring_memswap(unsigned char* buf,size_t bufSize,size_t pos,size_t leftLen,size_t rightLen){
        pos=__ring_pos(pos,bufSize);
        if ((leftLen==0)||(rightLen==0)) return;
        _ring_reverse(buf,bufSize,pos,leftLen+rightLen);
        _ring_reverse(buf,bufSize,pos,rightLen);
        _ring_reverse(buf,bufSize,pos+rightLen,leftLen);
    }

    // Execute read commands for one window, computed on-the-fly from winInfo
    static hpatch_BOOL _exec_window_read(hcache_window_old_mt_t* self,
                                         hpatch_StreamPos_t oldPos, size_t bufPos, size_t len) {
        if (len == 0) return hpatch_TRUE;
        bufPos=__ring_pos(bufPos,self->bufSize);
        if (!_buf_range_wraps(bufPos, len, self->bufSize)) {
            return self->old_stream->read(self->old_stream, oldPos,
                    self->buf + bufPos, self->buf + bufPos + len);
        } else {
            size_t part1 = self->bufSize - bufPos;
            if (!self->old_stream->read(self->old_stream, oldPos,
                    self->buf + bufPos, self->buf + bufPos + part1))
                return hpatch_FALSE;
            return self->old_stream->read(self->old_stream, oldPos + part1,
                    self->buf, self->buf + (len - part1));
        }
    }

// ---- async thread ----

static void _hcache_window_old_mt_thread(int threadIndex, void* workData) {
    hcache_window_old_mt_t* self = (hcache_window_old_mt_t*)workData;
    hpatch_StreamPos_t lastOldPos=0;
    hpatch_StreamPos_t lastOldEnd=0;
    size_t lastBufPos=0;
    while (!hpatch_mt_isOnFinish(self->mt_base.h_mt)){
        size_t localUnprocessedCount=0;
        size_t localUnprocessedWinStart;

        c_locker_enter(self->mt_base._locker);
       if ((!self->mt_base.isOnError) && (self->winCount == self->readyWinCount)&&(self->leaveWindowCount>0)) 
            c_condvar_wait(self->mt_base._waitCondvar, self->mt_base._locker);
        if ((!self->mt_base.isOnError) && (self->winCount>self->readyWinCount)) {
            localUnprocessedCount = self->winCount - self->readyWinCount;
            localUnprocessedWinStart = self->winStart+self->readyWinCount;
        }
        c_locker_leave(self->mt_base._locker);

        {// Execute reads for unprocessed windows, checking protected region overlap per window
            size_t winIdx;
            for (winIdx=0;winIdx<localUnprocessedCount;++winIdx) {
                const _oldwin_info_t* winInfo=&self->winInfos[(localUnprocessedWinStart + winIdx) & _kWinMask];
                size_t winLen = (size_t)winInfo->len;
                hpatch_StreamPos_t winPos = winInfo->oldPos;
                size_t winBufPos = lastBufPos;
                hpatch_BOOL isReadOk=hpatch_TRUE;
                hpatch_StreamPos_t reuseLen,reuseOff,noOverlapLeft;
                _compute_window_overlap(lastOldPos,lastOldEnd,winPos,winPos+winLen,&reuseLen,&reuseOff,&noOverlapLeft);
                // Wait until this window's write doesn't overlap with protected region
                c_locker_enter(self->mt_base._locker);
                while ((!self->mt_base.isOnError)
                      &&_buf_ranges_overlap(winBufPos,winLen-(size_t)reuseLen,self->protectedBufPos,self->protectedLen,self->bufSize)) {
                    c_condvar_wait(self->mt_base._waitCondvar, self->mt_base._locker);
                }
                // Mark this window's data as protected (it will be written next)
                self->protectedLen += winLen-(size_t)reuseLen;
                c_locker_leave(self->mt_base._locker);
                if (self->mt_base.isOnError) break;
                
                if (noOverlapLeft>0)
                    isReadOk=_exec_window_read(self,winPos,winBufPos,(size_t)noOverlapLeft);
                if (isReadOk&&(noOverlapLeft+reuseLen<winLen))
                    isReadOk=_exec_window_read(self,winPos+noOverlapLeft+reuseLen,(size_t)(winBufPos+noOverlapLeft),
                                               (size_t)(winLen-noOverlapLeft-reuseLen));
                if (!isReadOk)
                    { hpatch_mt_base_setOnError_(&self->mt_base); break; }
                lastOldPos=winPos;
                lastOldEnd=winPos+winLen;
                lastBufPos=__ring_pos(lastBufPos+winLen-(size_t)reuseLen,self->bufSize);

                // Signal this window's completion
                c_locker_enter(self->mt_base._locker);
                ++self->readyWinCount;
                c_condvar_signal(self->mt_base._waitCondvar);
                c_locker_leave(self->mt_base._locker);
            }
        }
    }

    hpatch_mt_base_aThreadEnd_(&self->mt_base);
}

    static hpatch_inline void _hcache_window_old_free(hcache_window_old_mt_t* self){
        if (!self) return;
        _hpatch_mt_base_free(&self->mt_base);
    }

// ---- public API ----
struct hcache_window_old_mt_t* hcache_window_old_open(void* pmem,size_t memSize,struct hpatch_mt_t* h_mt,
                                                      unsigned char* buf, size_t bufSize,
                                                      const hpatch_TStreamInput* old_stream,hpatch_StreamPos_t windowCount){
    hcache_window_old_mt_t* self=pmem;
    if (memSize<hcache_window_old_mt_t_memSize()) return 0;
    memset(self,0,sizeof(*self));
    self->buf = buf;
    self->bufSize = bufSize;
    self->old_stream = old_stream;
    self->leaveWindowCount = windowCount;

    if (!_hpatch_mt_base_init(&self->mt_base,h_mt,0,0))
        goto _on_error;

    if (self->leaveWindowCount>0){
        if (!hpatch_mt_base_aThreadBegin_(&self->mt_base,_hcache_window_old_mt_thread,self))
            goto _on_error;
    }
    return self;

_on_error:
    _hcache_window_old_free(self);
    return 0;
}

void hcache_window_old_prepareBatch(struct hcache_window_old_mt_t* self,hpatch_size_t batchCount,
                                    const _oldwin_info_t* winInfos,hpatch_size_t posStart,hpatch_size_t posMask){
    c_locker_enter(self->mt_base._locker);
    {
        hpatch_size_t i;
        assert(batchCount+self->winCount<=hpatch_kMaxWindowMetaCount);
        for (i = 0; i < batchCount; ++i) {
            size_t idx = (posStart + i) & posMask;
            size_t wiIdx = (self->winStart + self->winCount) & _kWinMask;
            assert(winInfos[idx].len <= self->bufSize);
            // Record window info
            self->winInfos[wiIdx]=winInfos[idx];
            ++self->winCount;
        }
    }
    c_condvar_signal(self->mt_base._waitCondvar);//Signal async thread: new windows available for processing
    c_locker_leave(self->mt_base._locker);
}

hpatch_BOOL hcache_window_old_getWindow(hcache_window_old_mt_t* self,
                                        hpatch_StreamPos_t windowOldPos,hpatch_StreamPos_t windowOldLength,
                                        unsigned char** out_seg1_begin, unsigned char** out_seg1_end,
                                        unsigned char** out_seg2_begin, unsigned char** out_seg2_end){
    _oldwin_info_t* wi = &self->winInfos[self->winStart];
    assert(self->winCount>0);// A batch must be prepared and not exhausted
    assert(wi->oldPos==windowOldPos);
    assert(wi->len==windowOldLength);
    if (self->readyWinCount<=0){// Wait until this window's data is ready
        c_locker_enter(self->mt_base._locker);
        while ((self->readyWinCount<=0) && (!self->mt_base.isOnError))
            c_condvar_wait(self->mt_base._waitCondvar, self->mt_base._locker);
        c_locker_leave(self->mt_base._locker);
        if (self->mt_base.isOnError) return hpatch_FALSE;
    }
    
    _ring_memswap(self->buf,self->bufSize,self->protectedBufPos,
                  (size_t)self->_reuseLen_back,(size_t)self->_noOverlapLeft_back);

    {// Return pointers into the ring buffer
        size_t       oldLen = (size_t)wi->len;
        size_t       bufPos = self->protectedBufPos;
        *out_seg1_begin = self->buf + bufPos;
        if (!_buf_range_wraps(bufPos,oldLen,self->bufSize)) {
            *out_seg1_end   = self->buf + bufPos + oldLen;
            *out_seg2_begin = 0;
            *out_seg2_end   = 0;
        } else {
            *out_seg1_end   = self->buf + self->bufSize;
            *out_seg2_begin = self->buf;
            *out_seg2_end   = self->buf + ((bufPos + oldLen) - self->bufSize);
        }
    }
    return hpatch_TRUE;
}

void hcache_window_old_finishWindow(hcache_window_old_mt_t* self){
    c_locker_enter(self->mt_base._locker);
    {
        const _oldwin_info_t* lastWinInfo= &self->winInfos[self->winStart];
        size_t lastWinLen = (size_t)lastWinInfo->len;
        hpatch_StreamPos_t reuseLen=0;
        hpatch_StreamPos_t noOverlapLeft=0;
        self->winStart = (self->winStart + 1) & _kWinMask;
        --self->winCount;
        --self->readyWinCount;
        --self->leaveWindowCount;
        if (self->leaveWindowCount>0){
            const _oldwin_info_t* winInfo = &self->winInfos[self->winStart];
            hpatch_StreamPos_t reuseOff;
            assert(self->winCount>0);
            _compute_window_overlap(lastWinInfo->oldPos,lastWinInfo->oldPos+lastWinLen,
                                    winInfo->oldPos,winInfo->oldPos+winInfo->len,&reuseLen,&reuseOff,&noOverlapLeft);
            if (reuseLen>0){
                hpatch_StreamPos_t protectedBufPos_next=self->protectedBufPos+(lastWinLen-reuseLen);
                _ring_memmove_to_right(self->buf,self->bufSize,self->protectedBufPos+(size_t)reuseOff,
                                       (size_t)protectedBufPos_next,(size_t)reuseLen);
            }
        }
        self->protectedLen -= (lastWinLen-(size_t)reuseLen);
        self->protectedBufPos=__ring_pos(self->protectedBufPos+lastWinLen-(size_t)reuseLen,self->bufSize);
        self->_reuseLen_back=(size_t)reuseLen;
        self->_noOverlapLeft_back=(size_t)noOverlapLeft;
    }
    c_condvar_signal(self->mt_base._waitCondvar); // wake async thread waiting on protected region
    c_locker_leave(self->mt_base._locker);
    if (self->leaveWindowCount==0)
        hpatch_mt_isOnFinish(self->mt_base.h_mt);
}

hpatch_BOOL hcache_window_old_close(hcache_window_old_mt_t* self){
    _hcache_window_old_free(self);
    return (self->leaveWindowCount==0)&&(!self->mt_base.isOnError);
}

#endif //_IS_USED_MULTITHREAD

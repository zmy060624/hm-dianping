package com.hmdp.utils;

public interface ILock {


    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间 过期后自释放
     * @return true表示获取锁成功 false表示失败
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}

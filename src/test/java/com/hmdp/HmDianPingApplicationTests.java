package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    ShopServiceImpl service;

    @Resource
    RedisIdWorker redisIdWorker;

    @Test
    void testSaveShop(){
     service.saveShop2Redis(1L,10L);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        ExecutorService es = Executors.newFixedThreadPool(500);
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task=()->{
            for(int i=0;i<100;i++){
                Long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            latch.countDown();
        };
        long begin=System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(task);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("耗时:"+(end-begin));
    }
}

package cn.itcast.temp;

/**
 * Author itcast
 * Desc 微软面试题-汉诺塔
 */
public class Interview_5 {
    public static void main(String[] args) {
        move(3, "X", "Y", "Z");
    }

    private static int num;
    /**
     * 定义一个方法实现将 n个盘子 从 from 借助于temp 移动到 to
     * @param n
     * @param from
     * @param temp
     * @param to
     */
    private static void move(int n, String from, String temp, String to) {
        if(n == 1){
            System.out.println("第"+ (++num) +"步:"+from + "--->" + to);
        }else{
            //1.将X上的n-1个盘子从X借助Z移动到Y
            move(n - 1, from, to, temp);
            //2.直接将X上的盘子移动到Z
            System.out.println("第"+ (++num) +"步:"+from + "--->" + to);
            //3.将Y上的n-1个盘子从Y借助X移动到Z
            move(n - 1,temp,from,to);
        }
    }
}

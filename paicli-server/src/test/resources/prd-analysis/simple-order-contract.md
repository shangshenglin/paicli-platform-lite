# 接口 / 数据契约

## order 对象
- orderId: string
- userId: string
- amount: number
- status: string (CREATED/PAID/REFUNDED/CANCELED)
- createdAt: datetime

## refund 对象
- refundId: string
- orderId: string
- reason: string
- amount: number
- status: string (APPLIED/APPROVED/REJECTED/REFUNDED)
- createdAt: datetime
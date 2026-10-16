from django.db import models
from apps.products.models import Product

class Order(models.Model):
    # Using BigIntegerField for halalas as requested to match DTO
    total_halalas = models.BigIntegerField()
    timestamp = models.DateTimeField(auto_now_add=True)
    status = models.CharField(max_length=50, default='PENDING')

class OrderItem(models.Model):
    order = models.ForeignKey(Order, related_name='items', on_delete=models.CASCADE)
    product = models.ForeignKey(Product, on_delete=models.PROTECT)
    quantity = models.IntegerField()
    price_halalas = models.BigIntegerField()

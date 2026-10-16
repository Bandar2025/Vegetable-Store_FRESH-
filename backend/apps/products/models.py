from django.db import models

class Category(models.Model):
    name = models.CharField(max_length=100)
    
    def __str__(self):
        return self.name

class Product(models.Model):
    name = models.CharField(max_length=200)
    category = models.ForeignKey(Category, on_delete=models.CASCADE)
    # Using DecimalField for precision as requested
    price = models.DecimalField(max_digits=10, decimal_places=2)
    description = models.TextField()
    image_url = models.CharField(max_length=500)
    unit = models.CharField(max_length=50)

    def __str__(self):
        return self.name

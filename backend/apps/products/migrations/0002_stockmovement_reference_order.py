# Generated migration linking StockMovement.reference_order to orders.Order without circular dependency

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    dependencies = [
        ('products', '0001_initial'),
        ('orders', '0001_initial'),
    ]

    operations = [
        migrations.AddField(
            model_name='stockmovement',
            name='reference_order',
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.PROTECT,
                related_name='stock_movements',
                to='orders.order',
            ),
        ),
        migrations.AddIndex(
            model_name='stockmovement',
            index=models.Index(fields=['reference_order'], name='idx_stock_move_ref_order'),
        ),
    ]
